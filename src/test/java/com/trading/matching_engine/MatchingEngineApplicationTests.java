package com.trading.matching_engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

class MatchingEngineApplicationTests {

	@Test
	void mainMethodExists() throws NoSuchMethodException {
		Method main = MatchingEngineApplication.class.getDeclaredMethod("main", String[].class);
		assertNotNull(main);
	}
}
