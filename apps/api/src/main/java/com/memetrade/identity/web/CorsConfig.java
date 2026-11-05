package com.memetrade.identity.web;

import com.memetrade.identity.infra.IdentityProperties;
import com.memetrade.shared.web.FilterOrder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.CorsFilter;

/** Registers the CORS filter before authentication so preflight requests never need a token. */
@Configuration
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> guestCorsFilter(IdentityProperties properties) {
        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(GuestCorsPolicy.source(properties.guestAppOrigin())));
        registration.setOrder(FilterOrder.CORS);
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
