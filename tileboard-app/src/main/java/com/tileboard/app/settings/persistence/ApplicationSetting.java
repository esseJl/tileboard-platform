package com.tileboard.app.settings.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Generic key/value row backing every {@link com.tileboard.app.settings.SettingKey}.
 *
 * <p>Intentionally schema-less (the value is opaque JSON): adding a new setting must
 * never require a migration. The alternative - one column/table per setting - would
 * mean every new configurable value needs its own DB migration and repository, which
 * is exactly the hard-coded shape this entity exists to avoid.
 */
@Entity
@Table(name = "app_settings")
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