package com.sneezecast.common.dto.metadata;

public interface CodeNameDescribable {

    String getDisplayName();

    default String getDescription() {
        return getDisplayName();
    }

    default CodeNameDescriptionMetadata toMetadata() {
        if (!(this instanceof Enum<?> enumValue)) {
            throw new IllegalStateException("CodeNameDescribable must be implemented by enum");
        }

        return CodeNameDescriptionMetadata.of(enumValue.name(), getDisplayName(), getDescription());
    }
}
