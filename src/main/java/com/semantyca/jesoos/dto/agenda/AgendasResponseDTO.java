package com.semantyca.jesoos.dto.agenda;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgendasResponseDTO {
    
    private Map<String, AgendaDTO> agendas = new HashMap<>();
    
    @JsonAnyGetter
    public Map<String, AgendaDTO> getAgendas() {
        return agendas;
    }
    
    public void addAgenda(String brandName, AgendaDTO agenda) {
        this.agendas.put(brandName, agenda);
    }
}
