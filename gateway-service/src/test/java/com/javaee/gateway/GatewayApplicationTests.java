package com.javaee.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class GatewayApplicationTests {

    @Test
    void applicationEntryPointExists() {
        assertNotNull(GatewayApplication.class);
    }
}
