package com.semantyca.djinn.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.UUID;

@Data
@AllArgsConstructor
public class IntroResult {
    private UUID songId;
    private String text;
    private boolean dialogue;
}
