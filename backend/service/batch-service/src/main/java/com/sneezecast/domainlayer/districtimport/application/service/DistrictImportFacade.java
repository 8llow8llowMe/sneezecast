package com.sneezecast.domainlayer.districtimport.application.service;

import com.sneezecast.domainlayer.districtimport.application.command.DistrictImportCommand;
import com.sneezecast.domainlayer.districtimport.application.exception.DistrictImportErrorCode;
import com.sneezecast.domainlayer.districtimport.application.exception.DistrictImportException;
import com.sneezecast.domainlayer.districtimport.application.model.DistrictImportResult;
import com.sneezecast.domainlayer.districtimport.application.port.in.DistrictImportUseCase;
import com.sneezecast.domainlayer.districtimport.application.port.out.DistrictBulkPort;
import com.sneezecast.domainlayer.districtimport.application.port.out.DistrictSourcePort;
import com.sneezecast.domainlayer.districtimport.application.service.processor.DistrictRetireGuardProcessor;
import com.sneezecast.domainlayer.districtimport.domain.model.DistrictSnapshot;
import com.sneezecast.domainlayer.districtimport.domain.model.ImportedDistrict;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 행정동 마스터 적재 오케스트레이터 (entity-design §3-1).
 *
 * <p>순서: 연도 역행 검사 → 원천 스냅샷 받기 → 스냅샷 검증 → [쓰기 트랜잭션: 현행 조회 → 폐지 비율 보호 규칙 → upsert → 폐지].
 * 보호 규칙까지 중 하나라도 실패하면 <b>아무것도 쓰지 않는다.</b>
 *
 * <p><b>트랜잭션을 메서드 전체에 걸지 않는다.</b> 원천 호출(약 36회, 수십 초)을 트랜잭션 안에서 기다리면 그동안 커넥션을 쥔다
 * (architecture-guide §3-1). DB 구간을 primary 매니저의 {@link TransactionTemplate} 둘로 좁힌다.
 * <ul>
 *   <li><b>원천 호출 전 읽기도 readOnly 트랜잭션으로 감싼다.</b> 스텝을 감싼 {@code taskletTransactionManager}(무자원)는 트랜잭션 동기화를 켜므로,
 *       감싸지 않은 {@code JdbcTemplate} 호출은 커넥션을 스레드에 묶어 둔 채 스텝이 끝날 때까지 놓지 않는다 — 원천 호출 내내 쥐게 된다.
 *       자체 트랜잭션으로 감싸면 커밋과 함께 반납된다.</li>
 *   <li><b>현행 조회 · 보호 규칙 · upsert · 폐지는 한 쓰기 트랜잭션이다.</b> 판정과 쓰기가 같은 시점 데이터를 보고, 규칙에 막히면 쓰기 전에
 *       예외 → 롤백이다. upsert 만 되고 폐지가 빠지거나 그 반대인 반쪽 상태도 남지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
public class DistrictImportFacade implements DistrictImportUseCase {

    /** 폐지 대상 코드를 로그에 몇 개까지 남길지. 대규모 폐지(허용된 경우) 때 로그 한 줄이 수천 개 코드로 불어나지 않게 한다. */
    private static final int RETIRING_CODES_LOG_LIMIT = 50;

    private final DistrictSourcePort districtSourcePort;
    private final DistrictBulkPort districtBulkPort;
    private final DistrictRetireGuardProcessor districtRetireGuardProcessor;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;

    /**
     * @param transactionManager primary(DataSource) 매니저. 스텝이 도는 {@code taskletTransactionManager} 는 무자원이라 쓰기를 묶지 못한다
     */
    public DistrictImportFacade(
        DistrictSourcePort districtSourcePort,
        DistrictBulkPort districtBulkPort,
        DistrictRetireGuardProcessor districtRetireGuardProcessor,
        @Qualifier("transactionManager") PlatformTransactionManager transactionManager
    ) {
        this.districtSourcePort = districtSourcePort;
        this.districtBulkPort = districtBulkPort;
        this.districtRetireGuardProcessor = districtRetireGuardProcessor;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public DistrictImportResult importDistricts(DistrictImportCommand command) {
        int year = command.year();
        rejectYearRegression(year);

        DistrictSnapshot snapshot = districtSourcePort.fetchSnapshot(year);
        validate(snapshot, year);

        Set<String> snapshotCodes = new HashSet<>();
        snapshot.districts().forEach(district -> snapshotCodes.add(district.code()));
        LocalDateTime syncedAt = LocalDateTime.now();
        int[] written = writeTransaction.execute(status -> write(command, snapshot, snapshotCodes, syncedAt));

        return new DistrictImportResult(year, snapshot.districts().size(), written[0], written[1]);
    }

    /** 쓰기 트랜잭션 안에서 돈다. 보호 규칙이 예외를 던지면 아무것도 쓰기 전에 롤백된다. */
    private int[] write(DistrictImportCommand command, DistrictSnapshot snapshot, Set<String> snapshotCodes, LocalDateTime syncedAt) {
        int year = command.year();
        Set<String> activeCodes = districtBulkPort.findActiveCodes();
        Set<String> retiringCodes = new TreeSet<>(activeCodes);
        retiringCodes.removeAll(snapshotCodes);

        log.info("District import retire check. year={} active={} retiring={} retiringCodes={}",
            year, activeCodes.size(), retiringCodes.size(), retiringCodes.stream().limit(RETIRING_CODES_LOG_LIMIT).toList());
        districtRetireGuardProcessor.check(year, activeCodes.size(), retiringCodes.size(), command.allowMassRetire());

        int upserted = districtBulkPort.upsertAll(snapshot.districts(), year, syncedAt);
        // 새 기준 연도 스냅샷에 없으면 직전 연도까지 유효했던 것으로 본다.
        int retired = districtBulkPort.retire(retiringCodes, year - 1, syncedAt);
        return new int[] {upserted, retired};
    }

    /**
     * 과거 연도로 다시 돌리면 그 뒤에 생긴 동이 전부 "새 스냅샷에 없는 코드" 가 되어 폐지되고, 그 뒤에 폐지된 동은 현행으로 되살아난다.
     * 같은 연도 재실행은 멱등이라 허용한다. 마지막 적재 연도의 정의는 {@link DistrictBulkPort#findLastLoadedYear()}.
     */
    private void rejectYearRegression(int year) {
        // 원천 호출 전이다. 커넥션이 스텝 끝까지 묶이지 않게 자체 readOnly 트랜잭션으로 읽고 바로 반납한다 (클래스 javadoc).
        OptionalInt lastLoadedYear = readTransaction.execute(status -> districtBulkPort.findLastLoadedYear());
        if (lastLoadedYear.isPresent() && year < lastLoadedYear.getAsInt()) {
            throw new DistrictImportException(DistrictImportErrorCode.YEAR_REGRESSION, year, lastLoadedYear.getAsInt());
        }
    }

    private void validate(DistrictSnapshot snapshot, int year) {
        List<ImportedDistrict> districts = snapshot.districts();
        if (districts.isEmpty() || snapshot.sidoCodes().isEmpty()) {
            throw new DistrictImportException(DistrictImportErrorCode.SNAPSHOT_EMPTY, year);
        }

        // 코드 형식(8자리 숫자)과 시도 · 시군구 접두는 ImportedDistrict 생성 때 이미 확인됐다.
        Set<String> seenCodes = new HashSet<>();
        Set<String> coveredSidoCodes = new HashSet<>();
        for (ImportedDistrict district : districts) {
            if (!seenCodes.add(district.code())) {
                throw new DistrictImportException(DistrictImportErrorCode.SNAPSHOT_DUPLICATE_CODE, year, district.code());
            }
            coveredSidoCodes.add(district.sidoCode());
        }

        List<String> missingSidoCodes = snapshot.sidoCodes().stream()
            .filter(sidoCode -> !coveredSidoCodes.contains(sidoCode))
            .sorted()
            .toList();
        if (!missingSidoCodes.isEmpty()) {
            throw new DistrictImportException(DistrictImportErrorCode.SNAPSHOT_SIDO_MISSING, year, missingSidoCodes);
        }
    }
}
