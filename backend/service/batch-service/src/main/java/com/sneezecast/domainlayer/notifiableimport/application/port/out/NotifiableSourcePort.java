package com.sneezecast.domainlayer.notifiableimport.application.port.out;

import com.sneezecast.domainlayer.notifiableimport.application.model.KdcaCallBudget;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableFetch;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRegionMeasure;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableRegionRow;
import com.sneezecast.domainlayer.notifiableimport.domain.model.NotifiableWeeklyRow;

/**
 * 질병관리청 전수신고 감염병 발생현황 원천. 외부 호출이라 트랜잭션 밖에서 부른다. 실패하면 {@code NotifiableImportException} 이다.
 *
 * <p>두 메서드 모두 HTTP 호출 직전마다 {@code budget.consume()} 하고, {@code totalCount} 까지 페이지를 넘겨 한 결과로 합친다.
 */
public interface NotifiableSourcePort {

    /** {@code year} 의 주별 전국 발생 수 ({@code /PeriodBasic}). {@code 계} 행은 빠진다. */
    NotifiableFetch<NotifiableWeeklyRow> fetchWeekly(int year, KdcaCallBudget budget);

    /** {@code year} 의 시도 연간 값 ({@code /Region}). 함께 오는 전국 행은 빠지고 {@code sidoCode} 행만 남는다. */
    NotifiableFetch<NotifiableRegionRow> fetchRegion(int year, NotifiableRegionMeasure measure, String sidoCode, KdcaCallBudget budget);
}
