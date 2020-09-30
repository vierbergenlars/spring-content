package org.springframework.data.rest.extensions.contentsearch;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.persistence.Id;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.content.commons.annotations.ContentId;
import org.springframework.content.commons.repository.ContentStore;
import org.springframework.content.commons.search.Searchable;
import org.springframework.content.commons.storeservice.StoreFilter;
import org.springframework.content.commons.storeservice.StoreInfo;
import org.springframework.content.commons.storeservice.Stores;
import org.springframework.content.commons.utils.BeanUtils;
import org.springframework.content.commons.utils.DomainObjectUtils;
import org.springframework.content.commons.utils.ReflectionService;
import org.springframework.content.commons.utils.ReflectionServiceImpl;
import org.springframework.content.rest.FulltextEntityLookupQuery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.support.Repositories;
import org.springframework.data.rest.webmvc.PersistentEntityResourceAssembler;
import org.springframework.data.rest.webmvc.RepositoryRestController;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.data.rest.webmvc.RootResourceInformation;
import org.springframework.data.rest.webmvc.support.DefaultedPageable;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.CollectionModel;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import internal.org.springframework.content.rest.controllers.BadRequestException;
import internal.org.springframework.content.rest.mappings.ContentHandlerMapping.StoreType;
import internal.org.springframework.content.rest.utils.ControllerUtils;
import internal.org.springframework.content.rest.utils.RepositoryUtils;
import internal.org.springframework.data.rest.extensions.contentsearch.DefaultEntityLookupStrategy;
import internal.org.springframework.data.rest.extensions.contentsearch.QueryMethodsEntityLookupStrategy;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@RepositoryRestController
public class ContentSearchRestController {

    private static final String ENTITY_CONTENTSEARCH_MAPPING = "/{repository}/searchContent";
    private static final String ENTITY_SEARCHMETHOD_MAPPING = "/{repository}/searchContent/findKeyword";

    private static Map<String, Method> searchMethods = new HashMap<>();

    private Repositories repositories;
    private Stores stores;
    private PagedResourcesAssembler<Object> pagedResourcesAssembler;
    private DefaultEntityLookupStrategy defaultLookupStrategy;
    private QueryMethodsEntityLookupStrategy qmLookupStrategy;

    private ReflectionService reflectionService;

    static {
        searchMethods.put("search", ReflectionUtils.findMethod(Searchable.class, "search", new Class<?>[] { String.class, Pageable.class }));
        searchMethods.put("findKeyword", ReflectionUtils.findMethod(Searchable.class, "findKeyword", new Class<?>[] { String.class }));
    }

    @Autowired
    public ContentSearchRestController(Repositories repositories, Stores stores, PagedResourcesAssembler<Object> assembler) {

        this.repositories = repositories;
        this.stores = stores;
        this.pagedResourcesAssembler = assembler;

        this.reflectionService = new ReflectionServiceImpl();
        this.defaultLookupStrategy = new DefaultEntityLookupStrategy();
        this.qmLookupStrategy = new QueryMethodsEntityLookupStrategy();
    }

    public void setReflectionService(ReflectionService reflectionService) {
        this.reflectionService = reflectionService;
    }

    public void setDefaultEntityLookupStrategy(DefaultEntityLookupStrategy lookupStrategy) {
        this.defaultLookupStrategy = lookupStrategy;
    }

    public void setQueryMethodsEntityLookupStrategy(QueryMethodsEntityLookupStrategy lookupStrategy) {
        this.qmLookupStrategy = lookupStrategy;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @StoreType("contentstore")
    @ResponseBody
    @RequestMapping(value = ENTITY_CONTENTSEARCH_MAPPING, method = RequestMethod.GET)
    public CollectionModel<?> searchContent(RootResourceInformation repoInfo, DefaultedPageable pageable, Sort sort, PersistentEntityResourceAssembler assembler, @PathVariable String repository, @RequestParam(name = "queryString") String queryString) {

        return searchContentInternal(repoInfo, repository, pageable, sort, assembler, "search", new String[] { queryString });
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @StoreType("contentstore")
    @ResponseBody
    @RequestMapping(value = ENTITY_SEARCHMETHOD_MAPPING, method = RequestMethod.GET)
    public CollectionModel<?> searchContent(RootResourceInformation repoInfo, DefaultedPageable pageable, Sort sort, PersistentEntityResourceAssembler assembler, @PathVariable String repository, @RequestParam(name = "keyword") List<String> keywords) {

        return searchContentInternal(repoInfo, repository, pageable, sort, assembler, "findKeyword", keywords.toArray(new String[] {}));
    }

    private CollectionModel<?> searchContentInternal(RootResourceInformation repoInfo, String repository, DefaultedPageable pageable, Sort sort, PersistentEntityResourceAssembler assembler, String searchMethod, String[] keywords) {

        StoreInfo[] infos = stores.getStores(ContentStore.class, new StoreFilter() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public boolean matches(StoreInfo info) {
                return repoInfo.getDomainType().equals(info.getDomainObjectClass());
            }
        });

        if (infos.length == 0) {
            throw new ResourceNotFoundException("Entity has no content associations");
        }

        if (infos.length > 1) {
            throw new IllegalStateException(String.format("Too many content assocation for Entity %s", repoInfo.getDomainType().getCanonicalName()));
        }

        StoreInfo info = infos[0];

        ContentStore<Object, Serializable> store = info.getImplementation(ContentStore.class);
        if (store instanceof Searchable == false) {
            throw new ResourceNotFoundException("Entity content is not searchable");
        }

        Method method = searchMethods.get(searchMethod);
        if (method == null) {
            throw new ResourceNotFoundException(String.format("Invalid search: %s", searchMethod));
        }

        if (keywords == null || keywords.length == 0) {
            throw new BadRequestException();
        }

        Class<?> returnType = returnType(info);
        if (returnType.isPrimitive() || returnType.equals(String.class) || returnType.equals(UUID.class)) {

            returnType = InternalResult.class;
        }

        List<Object> intermediateResults = (List<Object>) reflectionService.invokeMethod(method, store, keywords[0], pageable.getPageable(), returnType);

        if (intermediateResults == null || intermediateResults.size() == 0) {
            return CollectionModel.empty();
        }

        final List<Object> results = new ArrayList<>();

        if (returnType.equals(InternalResult.class)) {

            boolean idFieldEqualsContentIdField = isIdFieldOverloaded(repoInfo.getDomainType());

            List<Object> entityIds = new ArrayList<>();
            List<Object> contentIds = new ArrayList<>();

            for (Object tempResult : intermediateResults) {
                InternalResult internalResult = (InternalResult)tempResult;
                if (internalResult.getId() != null) {
                    entityIds.add(internalResult.getId());
                } else if (idFieldEqualsContentIdField) {
                    entityIds.add(internalResult.getContentId());
                } else if (internalResult.getContentId() != null) {
                    contentIds.add(internalResult.getContentId());
                }
            }

            RepositoryInformation ri = RepositoryUtils.findRepositoryInformation(repositories, repository);
            Class<?> domainClass = ri.getDomainType();
            repositories.getRepositoryFor(domainClass).ifPresent(r -> {

                Method findAllByIdMethod = ReflectionUtils.findMethod(CrudRepository.class, "findAllById", Iterable.class);
                Iterable entities = (Iterable) ReflectionUtils.invokeMethod(findAllByIdMethod, r, entityIds);
                for (Object entity : entities) {
                    results.add(entity);
                }
            });

            if (contentIds.size() > 0) {
                if (ri != null) {
                    if (ri.getQueryMethods()
                            .filter(m -> m.getAnnotation(FulltextEntityLookupQuery.class) != null)
                            .isEmpty()) {

                        defaultLookupStrategy.lookup(repoInfo, ri, contentIds, results);
                    } else {

                        qmLookupStrategy.lookup(repoInfo, ri, contentIds, results);
                    }
                }
            }

            return ControllerUtils.toCollectionModel(results, pagedResourcesAssembler, assembler, domainClass);
        } else {
            results.addAll(intermediateResults);
            return ControllerUtils.toCollectionModel(results, pagedResourcesAssembler, null, intermediateResults.get(0).getClass());
        }
    }

    private Class<?> returnType(StoreInfo info) {
        Class<?> storeInterfaceClass = info.getInterface();
        Class<?> searchReturnType = String.class;
        for (Type t : storeInterfaceClass.getGenericInterfaces()) {
            if (t.getTypeName().startsWith("org.springframework.content.commons.search")) {
                if (t instanceof ParameterizedType) {
                    Type[] fragmentGenericTypes = ((ParameterizedType)t).getActualTypeArguments();
                    if (fragmentGenericTypes != null) {
                        searchReturnType = (Class<?>)fragmentGenericTypes[0];
                    }
                }
            }
        }
        return searchReturnType;
    }

    private boolean isIdFieldOverloaded(Class<?> domainClass) {

        Field idField = DomainObjectUtils.getIdField(domainClass);
        Field contentIdField = BeanUtils.findFieldWithAnnotation(domainClass, ContentId.class);

        if (idField.equals(contentIdField)) {
            return true;
        }
        return false;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    public static class InternalResult {

        @Id
        private Object id;

        @ContentId
        private Object contentId;
    }
}
