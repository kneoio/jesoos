package com.semantyca.djinn.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class BrandListenerDTO {
    private UUID id;
    private UUID brandId;
    @JsonProperty("listener")
    private ListenerDTO listenerDTO;
}