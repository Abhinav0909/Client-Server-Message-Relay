package com.cisco.webex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.concurrent.CountDownLatch;

@SpringBootApplication
public class WebexApplication {

	public static void main(String[] args) throws InterruptedException {
		SpringApplication.run(WebexApplication.class, args);
		// This is a non-web application, and TCPRelayServer's accept loop runs
		// on a virtual thread, which is always daemon and can't be changed —
		// so nothing else keeps the JVM alive once main() returns. Block here;
		// SIGTERM/Ctrl+C still triggers Spring Boot's registered shutdown hook
		// (SmartLifecycle.stop()) independently of what main() is doing.
		new CountDownLatch(1).await();
	}

}
