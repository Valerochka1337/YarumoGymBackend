package tech.valerochkagym.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import tech.valerochkagym.repository.catalog.CatalogStateRepository
import tech.valerochkagym.security.BearerFilter
import tech.valerochkagym.security.RateLimiter
import tech.valerochkagym.service.auth.AuthService

@Configuration
class Security {
  @Bean
  fun filterChain(
    http: HttpSecurity,
    auth: AuthService,
    healthDisclosure: tech.valerochkagym.service.health.HealthAiDisclosureService,
    limits: RateLimiter,
    catalog: tech.valerochkagym.repository.catalog.CatalogStateRepository,
  ): SecurityFilterChain =
    http
      .csrf { it.disable() }
      .cors { it.disable() }
      .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
      .requestCache { it.disable() }
      .formLogin { it.disable() }
      .httpBasic { it.disable() }
      .authorizeHttpRequests {
        it
          .requestMatchers("/v1/auth/**")
          .permitAll()
          .requestMatchers(
            HttpMethod.GET,
            "/v1/catalog",
            "/v1/routine-shares/preview/**",
            "/r/**",
            "/.well-known/assetlinks.json",
            "/health",
            "/actuator/health/**",
          )
          .permitAll()
          .anyRequest()
          .authenticated()
      }
      .exceptionHandling {
        it.authenticationEntryPoint { _, response, _ ->
          response.status = 401
          response.contentType = "application/json"
          response.writer.write(
            "{\"code\":\"unauthorized\",\"message\":\"Authentication required\"}"
          )
        }
      }
      .addFilterBefore(
        BearerFilter(auth, healthDisclosure, limits, catalog),
        UsernamePasswordAuthenticationFilter::class.java,
      )
      .build()
}
