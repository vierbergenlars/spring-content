package com.emc.spring.content.fs.boot;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

import com.emc.spring.content.commons.annotations.Content;
import com.emc.spring.content.commons.annotations.ContentId;
import com.emc.spring.content.commons.repository.ContentStore;

import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import org.junit.Test;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;

public class ContentFilesystemAutoConfigurationTests {

	@Test
	public void contextLoads() {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.register(TestConfig.class);
		context.refresh();

		MatcherAssert.assertThat(context.getBean(TestEntityContentRepository.class), CoreMatchers.is(CoreMatchers.not(CoreMatchers.nullValue())));

		context.close();
	}

	@Configuration
	@AutoConfigurationPackage
	@EnableAutoConfiguration
	public static class TestConfig {
	}

	@Entity
	@Content
	public class TestEntity {
		@Id
		@GeneratedValue(strategy = GenerationType.AUTO)
		private long id;
		@ContentId
		private String contentId;
	}

	public interface TestEntityRepository extends JpaRepository<TestEntity, Long> {
	}

	public interface TestEntityContentRepository extends ContentStore<TestEntity, String> {
	}
}
