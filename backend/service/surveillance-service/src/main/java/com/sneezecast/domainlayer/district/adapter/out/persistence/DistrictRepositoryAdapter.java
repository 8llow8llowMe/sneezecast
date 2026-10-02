package com.sneezecast.domainlayer.district.adapter.out.persistence;

import com.sneezecast.domainlayer.district.adapter.out.persistence.repository.DistrictRepository;
import com.sneezecast.domainlayer.district.application.mapper.DistrictMapper;
import com.sneezecast.domainlayer.district.application.port.out.DistrictRepositoryPort;
import com.sneezecast.domainlayer.district.domain.model.District;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DistrictRepositoryAdapter implements DistrictRepositoryPort {

    private final DistrictRepository districtRepository;
    private final DistrictMapper districtMapper;

    @Override
    public List<District> searchActive(String keyword, int limit) {
        return districtRepository.searchActive(keyword, limit).stream()
            .map(districtMapper::toDomainFromEntity)
            .toList();
    }

    @Override
    public Optional<District> findByCode(String code) {
        return districtRepository.findByCode(code).map(districtMapper::toDomainFromEntity);
    }
}
