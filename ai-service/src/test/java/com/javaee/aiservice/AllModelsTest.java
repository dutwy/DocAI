package com.javaee.aiservice;

import com.javaee.aiservice.agent.AIService;
import com.javaee.aiservice.factory.AIServiceFactory;
import com.javaee.aiservice.model.ModelType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class AllModelsTest {

    @Autowired
    private AIServiceFactory aiServiceFactory;

    @Test
    void testProfileRegistersModelsWithoutEnablingExternalCalls() {
        Map<ModelType, AIService> services = aiServiceFactory.getAllServices();
        assertFalse(services.isEmpty(), "测试应覆盖已配置的模型");
        services.forEach((model, service) -> {
            assertNotNull(service, () -> "模型未完成注册: " + model.getCode());
            assertFalse(service.isAvailable(),
                    () -> "test profile 不得启用外部模型: " + model.getCode());
        });
    }
}
