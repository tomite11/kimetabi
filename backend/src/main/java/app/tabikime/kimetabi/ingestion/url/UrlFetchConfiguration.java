package app.tabikime.kimetabi.ingestion.url;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UrlFetchProperties.class)
public class UrlFetchConfiguration {
}
