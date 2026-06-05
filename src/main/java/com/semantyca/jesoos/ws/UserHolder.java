package com.semantyca.jesoos.ws;

import com.semantyca.core.model.user.IUser;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class UserHolder {
    private volatile IUser user;

    public UserHolder(IUser user) {
        this.user = user;
    }

}
