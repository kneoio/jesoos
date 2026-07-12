package com.semantyca.jesoos.model.stream;

import com.semantyca.mixpla.model.brand.AiOverriding;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.officeframe.model.cnst.CountryCode;

import java.time.ZoneId;

/**
 * In-memory-only default carrier for owner-scoped OTS streams that have no real {@link Brand}.
 * Never persisted, never a valid {@code SongSourceScope.BrandScope}, RabbitMQ routing key, or
 * {@code DjStateService} identity — see OTS_WORKFLOW.md §2.
 */
public class SyntheticBrand extends Brand {

    public SyntheticBrand(ZoneId timeZone) {
        setTimeZone(timeZone);
        setBitRate(64);
        setAiOverriding(new AiOverriding());
        setCountry(CountryCode.UNKNOWN);
    }
}
