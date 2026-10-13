package io.github.yuri_hack.rag_knowledge_qa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文装配冒烟测试：需要真实 dev 中间件（MySQL/Redis/Milvus）与 API key 环境变量。
 * 缺密钥时跳过而非报错——否则本地/CI 会把环境问题误报成装配故障。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ALIYUN_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "ALIYUN_OPENSEARCH_API_KEY", matches = ".+")
class RagEnterpriseKnowledgeBaseApplicationTests {

	@Test
	void contextLoads() {
	}

}
