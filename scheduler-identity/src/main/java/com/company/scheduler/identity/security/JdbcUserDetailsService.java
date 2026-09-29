package com.company.scheduler.identity.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class JdbcUserDetailsService implements UserDetailsService {
    private final JdbcTemplate jdbc;

    public JdbcUserDetailsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return jdbc.query("SELECT id, password_hash, enabled FROM app_user WHERE username = ?",
                (rs, row) -> User.withUsername(rs.getString("id"))
                        .password(rs.getString("password_hash")).authorities("USER")
                        .disabled(!rs.getBoolean("enabled")).build(), username)
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
