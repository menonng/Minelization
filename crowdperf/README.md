# CrowdPerf

마인크래프트 26.3 (Fabric) 클라이언트 전용 최적화 모드. 주민 수천 명이 한 화면에 보여도 가볍게 그린다. Minelization(Grand Strategy) 모드를 위해 만들었지만 그 모드 없이도, 어느 서버에서나 동작한다(바닐라 주민·떠돌이 상인 대상).

## 무엇을 하나

바닐라는 매 프레임, 보이는 주민 한 명마다 보이는지 검사 → 렌더 상태 추출(이름표·발광·속성) → 모델 자세 계산 → 큐브마다 정점 생성을 다시 한다. 주민 1,650명이 보일 때 이것이 렌더 스레드의 대부분이었다(JFR 측정: 정점 27%, 추출 15%, 검사 15%, 자세 10%+).

CrowdPerf는:

1. **먼 주민을 한 덩어리로 그린다.** `batchDistance`(기본 32칸) 밖 주민은 정상 렌더러가 건너뛰고, 틱마다 한 번 전부 정점 버퍼 하나에 쓴다. 바닐라 모델을 기본 자세로 한 번 구워 둔 메시를 위치·몸 방향만 바꿔 복사한다. 버퍼는 GPU에 한 번 올리고, 프레임마다 그리기 한 번(바닐라 `entity_cutout` 파이프라인, 같은 조명·안개·빛 지도)으로 끝난다.
2. **옷을 한 장으로 합친다.** 피부 + 지역 옷 + 직업 옷은 같은 UV를 쓰므로, 알파 컷아웃 규칙(0.1)과 모자 숨김 규칙(`VillagerMetadataSection`)대로 미리 한 장으로 합쳐 아틀라스(1024×1024, 64×64 칸 256개)에 둔다. 덩어리 전체가 텍스처 하나로 그려지고, 먼 주민도 옷을 입고 보인다. 리소스 팩이 바뀌면 다시 합친다.
3. **거리 단계.** `lowDetailDistance`(64칸) 밖은 코·모자를 뺀 메시, `farDistance`(96칸) 밖은 두 번째 버퍼에 담아 `farRebuildTicks`(4틱)마다만 다시 만든다.
4. **가벼운 틱.** 덩어리에 든 주민은 위치 보간과 몸 방향만 계산하고 나머지 클라이언트 틱을 건너뛴다.
5. **가까운 주민의 LOD.** 덩어리를 끄면, `detailDistance`(48칸) 밖 주민은 옷 겹·든 물건·머리 장식·그림자를 빼고 그린다. 주민은 바닐라의 64칸 대신 렌더 거리까지 보인다(`renderToViewDistance`).

기본 자세로는 안 되는 주민(발광, 이름표 표시, 다침, 죽는 중, 자는 중, 탈것, 끈, 투명)과 가까운 주민은 언제나 바닐라 그대로 그린다.

## 결과

같은 장면(주민 약 1,400~1,500명 보임, Sodium·Entity Culling·ImmediatelyFast 설치):

| | FPS |
| --- | --- |
| CrowdPerf 끔 | 223 ~ 247 |
| CrowdPerf 켬 | 1,427 ~ 1,920 |

덩어리 다시 만들기는 주민 1,260명에 틱당 약 1.8ms.

## 설정 (`config/crowdperf.json`)

| 키 | 기본 | 뜻 |
| --- | --- | --- |
| `batch` | true | 먼 주민을 덩어리로 그리기 |
| `batchDistance` | 32 | 이 거리(칸) 밖부터 덩어리 |
| `lowDetailDistance` | 64 | 이 밖은 코·모자 없는 메시 |
| `farDistance` · `farRebuildTicks` | 96 · 4 | 이 밖은 따로 담아 몇 틱마다만 다시 만들기 |
| `rebuildTicks` | 1 | 가까운 덩어리를 다시 만드는 간격(틱) |
| `lightTick` | true | 덩어리 주민의 가벼운 틱 |
| `detailDistance` | 48 | 모델로 그리는 주민이 옷 겹 등을 빼는 거리 |
| `renderToViewDistance` | true | 주민을 렌더 거리까지 그리기 |
| `maxBatched` | 30000 | 덩어리에 넣는 최대 수 |

실행 인자 `-Dcrowdperf.<키>=<값>`은 그 실행에만 적용된다(파일에 쓰지 않음). `-Dcrowdperf.log=true`면 10초마다 통계를 남긴다.

## 명령 (클라이언트)

| 명령 | 설명 |
| --- | --- |
| `/crowdperf` | 상태와 통계(덩어리 주민 수, 면 수, 다시 만들기 시간, 아틀라스 모습 수) |
| `/crowdperf on` · `off` | 덩어리 켜기 / 끄기 |
| `/crowdperf distance <칸>` | 덩어리 시작 거리 |
| `/crowdperf reload` | 설정 파일과 아틀라스 다시 읽기 |

## 빌드

저장소 루트에서 `./gradlew :crowdperf:build` → `crowdperf/build/libs/crowdperf-<버전>.jar`. `./gradlew collect`는 메인 모드와 함께 `dist/`에 모은다. 개발용 클라이언트(`./gradlew runClient`)는 이 모드를 함께 불러온다. 테스트(`./gradlew :crowdperf:test`)는 구운 메시가 바닐라와 같은 자리(발이 땅, 코가 앞, UV가 피부 안)에 있는지 확인한다.

## 참고한 것

- [Entity Culling](https://github.com/tr7zw/EntityCulling): 가려진 엔티티 생략과 안 보이는 엔티티의 틱 생략. (코드는 가져오지 않음)
- EntityBoost, SmartEntityOptimizer: 먼 몹의 작은 장식 생략, 갱신 빈도 낮추기.
- ELO (Entity LOD Optimizer): 거리별 처리 단계.
- Sodium: 큐브 정점을 메모리에 직접 쓰기.
- 바닐라 `CloudRenderer`: GPU 버퍼를 바뀔 때만 다시 만들고 매 프레임 그리기.

## 한계

- 덩어리 주민은 기본 자세(팔짱, 다리 정지)로 서 있고 고개를 따로 돌리지 않는다. 32칸 밖에서는 몇 픽셀이다.
- 덩어리는 벽 뒤 주민도 그린다(GPU 깊이 검사로 가려짐). 그리기 한 번이라 비용은 작다.
- Iris 셰이더 팩과 함께 쓰는 경우는 시험하지 않았다.
