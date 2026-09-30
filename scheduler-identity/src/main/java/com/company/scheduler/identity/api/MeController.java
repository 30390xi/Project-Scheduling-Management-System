package com.company.scheduler.identity.api;

import com.company.scheduler.identity.service.PasswordService;
import com.company.scheduler.shared.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {
    private final PasswordService passwords;

    public MeController(PasswordService passwords) {
        this.passwords = passwords;
    }

    @PostMapping("/password")
    public ApiResponse<String> changePassword(Authentication authentication,
                                              @Valid @RequestBody ChangePasswordRequest body,
                                              HttpServletRequest request) {
        String sessionId = request.getSession(false) != null ? request.getSession(false).getId() : null;
        passwords.changeOwnPassword(UUID.fromString(authentication.getName()),
                body.currentPassword(), body.newPassword(), sessionId);
        return ApiResponse.of("password_changed");
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 72) String currentPassword,
                                        @NotBlank @Size(max = 72) String newPassword) {}
}
