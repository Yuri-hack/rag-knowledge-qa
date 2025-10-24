package io.github.yuri_hack.rag_knowledge_qa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class RagEnterpriseKnowledgeBaseApplication {

	public static void main(String[] args) {
		SpringApplication.run(RagEnterpriseKnowledgeBaseApplication.class, args);
	}

}
