package com.sneezecast.domainlayer.aggregate.application.port.in;

import com.sneezecast.domainlayer.aggregate.application.model.AggregateWeekResult;
import java.util.List;

/**
 * 집계 재계산 · 주 마감 진입점 (entity-design §2-2). 웹이 아니라 스케줄러가 부르므로 {@code *WebUseCase} 가 아니다.
 */
public interface AggregateJobUseCase {

    /**
     * 현재 주(KST)를 원시 보고에서 다시 세어 행정동별로 덮어쓴다 (판정 {@code WeekState.IN_PROGRESS}). 결과에 없는 기존 행은 0 과 LOW_SAMPLE 로
     * 덮어쓴다. 같은 입력이면 몇 번을 불러도 결과가 같다.
     */
    AggregateWeekResult recalculateCurrentWeek();

    /**
     * 마감할 때가 지난 주를 오래된 순으로 마지막 재계산(판정 {@code WeekState.CLOSED})하고 마감한다 — 놓친 주(서버 중단)도 함께 따라잡는다. 주마다
     * 따로 커밋하고, 한 주가 실패하면 뒤 주는 다음 실행으로 미룬다(뒤 주의 판정이 앞 주를 전주 · 기준선으로 본다).
     *
     * @return 마감한 주들의 결과, 오래된 순. 마감할 주가 없으면 빈 목록
     */
    List<AggregateWeekResult> closeOverdueWeeks();
}
