package io.kartographer.integration

import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("io/kartographer/integration")
@ConfigurationParameter(key = "cucumber.ansi-colors.disabled", value = "true")
class CucumberTestRunner
