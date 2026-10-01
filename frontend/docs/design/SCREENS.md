# 화면 대응표

| ID | 화면 | 모바일 | 태블릿 | 데스크톱 | 제안 라우트 |
| --- | --- | --- | --- | --- | --- |
| S01 | 시작 | Start | Start-T | Start-D | `/start` |
| S02-1 | 동네 선택 | Setup-1 | Setup-1-T | Setup-1-D | `/setup/region` |
| S02-2 | 성인 확인 | Setup-2 | Setup-2-T | Setup-2-D | `/setup/adult` |
| S02-3 | 동의 | Setup-3 | Setup-3-T | Setup-3-D | `/setup/consent` |
| S03 | 홈 (평소 수준·조금·많이·자료 부족) | Home-good, Home-normal, Home, Home-pending | Tablet, Home-nodata-T | Desktop, Home-nodata-D | `/` |
| S04 | 지도 | Map-collapsed, Map-expanded, Map-nodata | Map-expanded-T, Map-nodata-T | Map-expanded-D, Map-nodata-D | `/map?region=` |
| S05 | 주간 건강 보고 (시트·대화상자) | Report-start, Report-symptom, Report-confirm, Report-edit | 같은 이름 + -T | 같은 이름 + -D | 홈 위 모달 `/?report=start` |
| S06 | 보고 완료 · 되돌리기 | Report-done, Report-done-ok | -T | -D | 모달 |
| S07 | 동네 안내 (발행·없음·정정) | Guide-published, Guide-none, Guide-corrected | -T | -D | `/notice/[region]/[week]` |
| S08 | 공식 정보 | Official | Official-T | Official-D | `/official` |
| S10 | 내 정보 (기본·알림 미지원) | Settings, Settings-nopush | -T | -D | `/me` |
| S11 | 판단 기준 설명 | Explain | Explain-T | Explain-D | 홈 위 모달 `/?explain=1` |
| S12 | 홈 화면 추가 안내 | Install | Install-T | Install-D | 모달 또는 `/install` |
| 상태 | 불러오는 중·오류·오프라인·알림 대신 홈 표시 | State-loading, State-error, State-offline, State-push-inapp | -T | -D | 공통 |
| A01–A02 | 운영자 검토 대기·후보 상세 | — | — | Admin | `/admin/review` |
| A03 | 발행 이력·정정·철회 | — | — | History | `/admin/history` |
| 시연 | 클릭 프로토타입 | Flow | — | — | (참고용) |

S09 학부모 그룹은 이번 범위가 아니라 만들지 않습니다.
