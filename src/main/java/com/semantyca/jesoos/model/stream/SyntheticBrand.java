package com.semantyca.jesoos.model.stream;

import com.semantyca.mixpla.model.brand.AiOverriding;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.brand.Owner;
import com.semantyca.officeframe.model.cnst.CountryCode;

import java.time.ZoneId;

public class SyntheticBrand extends Brand {

    public SyntheticBrand(ZoneId timeZone, long authorId) {
        setTimeZone(timeZone);
        setBitRate(64);
        setAiOverriding(new AiOverriding());
        setCountry(CountryCode.UNKNOWN);
        Owner owner = new Owner();
        owner.setUserId(authorId);
        setOwner(owner);
    }
}
