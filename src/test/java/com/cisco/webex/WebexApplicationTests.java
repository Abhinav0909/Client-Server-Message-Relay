package com.cisco.webex;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "relay.transport.port=0")
class WebexApplicationTests {

	@Test
	void contextLoads() {
	}

}
