package com.platform.movieservice.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserAccessCheckResponse {

    @JsonProperty("authorId")
    private UUID authorId;

    @JsonProperty("viewerId")
    private UUID viewerId;

    @JsonProperty("isPrivate")
    private boolean isPrivate;

    @JsonProperty("isFollowing")
    private boolean isFollowing;

    @JsonProperty("canView")
    private boolean canView;

    @JsonProperty("private")
    public void setPrivateAlias(boolean isPrivate) {
        this.isPrivate = isPrivate;
    }

    @JsonProperty("following")
    public void setFollowingAlias(boolean isFollowing) {
        this.isFollowing = isFollowing;
    }
}
