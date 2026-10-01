import type { District } from './types'

/**
 * 행정동 목 데이터. **예시 값이다.** 코드는 SGIS 형식(8자리)만 맞춘 것이라 실제 경계와 대조하지 않았다.
 *
 * 행정동 연동 이슈(백엔드 `GET /api/v1/districts`, #60)에서 이 파일을 지운다.
 * 이름이 같은 동(신사동)과 번호가 붙은 동(역삼1동 · 역삼2동)을 넣어 시군구로 가려 보이는지 확인한다.
 */
export const DISTRICT_MOCKS: readonly District[] = [
  { code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구' },
  { code: '11680650', name: '역삼2동', sigungu: '서울특별시 강남구' },
  { code: '11680510', name: '신사동', sigungu: '서울특별시 강남구' },
  { code: '11620685', name: '신사동', sigungu: '서울특별시 관악구' },
  { code: '11440660', name: '서교동', sigungu: '서울특별시 마포구' },
  { code: '11440680', name: '합정동', sigungu: '서울특별시 마포구' },
  { code: '11440690', name: '망원1동', sigungu: '서울특별시 마포구' },
  { code: '11440700', name: '망원2동', sigungu: '서울특별시 마포구' },
  { code: '11110530', name: '사직동', sigungu: '서울특별시 종로구' },
  { code: '11110540', name: '삼청동', sigungu: '서울특별시 종로구' },
  { code: '11305595', name: '수유1동', sigungu: '서울특별시 강북구' },
  { code: '26350525', name: '우동', sigungu: '부산광역시 해운대구' },
  { code: '26350530', name: '중동', sigungu: '부산광역시 해운대구' },
  { code: '41135580', name: '정자1동', sigungu: '경기도 성남시 분당구' },
  { code: '41135590', name: '정자2동', sigungu: '경기도 성남시 분당구' },
]
