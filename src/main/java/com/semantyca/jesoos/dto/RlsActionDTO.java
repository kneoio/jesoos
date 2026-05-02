package com.semantyca.jesoos.dto;

import com.semantyca.core.model.cnst.RlsActionType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class RlsActionDTO {
    private RlsActionType action;
    private long userId;
    private boolean canEdit;
    private boolean canDelete;
}
