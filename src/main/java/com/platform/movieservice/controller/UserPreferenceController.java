package com.platform.movieservice.controller;

import com.platform.movieservice.context.UserContext;
import com.platform.movieservice.dto.request.UserPreferenceRequest;
import com.platform.movieservice.dto.response.PreferenceOptionsResponse;
import com.platform.movieservice.dto.response.UserPreferenceResponse;
import com.platform.movieservice.service.UserPreferenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/movies/preferences")
@RequiredArgsConstructor
public class UserPreferenceController {

    private final UserPreferenceService userPreferenceService;

    @PutMapping
    public ResponseEntity<UserPreferenceResponse> updatePreferences(
            @Valid @RequestBody UserPreferenceRequest request) {
        UUID userId = UserContext.getRequiredUserId();
        return ResponseEntity.ok(userPreferenceService.savePreferences(userId, request));
    }

    @GetMapping("/me")
    public ResponseEntity<UserPreferenceResponse> getMyPreferences() {
        UUID userId = UserContext.getRequiredUserId();
        UserPreferenceResponse preferences = userPreferenceService.getPreferences(userId);
        return ResponseEntity.ok(preferences);
    }

    @GetMapping("/options")
    public ResponseEntity<PreferenceOptionsResponse> getPreferenceOptions() {
        return ResponseEntity.ok(userPreferenceService.getAllOptions());
    }
}
