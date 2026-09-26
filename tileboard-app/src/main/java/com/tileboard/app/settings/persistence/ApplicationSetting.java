package com.tileboard.app.settings.persistence;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

import java.time.Instant;

/**
 * Generic key/value row backing every {@link com.tileboard.app.settings.SettingKey}.
 *
 * <p>Intentionally schema-less (the value is opaque JSON): adding a new setting must
 * never require a schema change. The alternative - one column/table per setting - would
 * mean every new configurable value needs its own column/table and repository, which
 * is exactly the hard-coded shape this entity exists to avoid.
 *
 * <p>The table is created and extended by Hibernate ({@code spring.jpa.hibernate.ddl-auto=update}).
 *
 * <p>{@code @Cacheable} + {@code @Cache} opt this entity into Hibernate's second-level cache
 * (see {@code application.yml} for the region-factory/provider wiring) - this is the ONLY
 * cache in front of settings reads; {@code JpaSettingsService} has no cache of its own and
 * simply calls the repository on every {@code get()}. {@code READ_WRITE} is used - not the
 * cheaper {@code READ_ONLY} - because {@link #value} is mutated in place by
 * {@code JpaSettingsService#persist} and this entity already carries a {@link Version} for
 * optimistic locking, which {@code READ_WRITE} relies on to detect stale cache entries.
 */
@Entity
@Table(name = "app_settings")
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE, region = "appSettings")
public class ApplicationSetting {

    @Id
    @Column(name = "setting_key", nullable = false, updatable = false, length = 200)
    private String key;

    @Column(name = "value_json", nullable = false, columnDefinition = "TEXT")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic locking: settings can be written from concurrent requests (e.g. two admins).
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ApplicationSetting() {
        // JPA
    }

    public ApplicationSetting(String key, String value, Instant updatedAt) {
        this.key = key;
        this.value = value;
        this.updatedAt = updatedAt;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getVersion() {
        return version;
    }
}