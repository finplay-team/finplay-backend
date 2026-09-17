package com.finplay.api.domain.auth.config;

import com.finplay.api.domain.auth.crypto.Sha256BcryptPasswordEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@Profile("!prod | web")
public class AuthCryptoConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new Sha256BcryptPasswordEncoder();
	}
}
