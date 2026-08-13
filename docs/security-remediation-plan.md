# 보안·품질 개선 실행 계획서

> 외부 감사 문서(Claude Code 전체 코드베이스 리뷰)를 **코드와 1:1 대조 검증**한 뒤 재정렬한 실행 계획이다.
> 감사 문서를 그대로 구현하면 안 되는 항목이 있어(오탐 1건, 원인 오진 1건, 도메인상 부적절 2건),
> 아래 §2 검증 로그를 먼저 읽고 착수할 것.
>
> 작성 기준일: 2026-08-13 · **최초 감사 기준 커밋: `a988113`**
>
> **브랜치 운영**: `dev`는 사용하지 않는다. 이슈별 `fix/#NN` 브랜치에서 작업하고 PR은 `main`으로 올린다.
>
> **line 번호 표기 규칙**: 이 문서의 `파일:줄번호`는 **감사 시점(`a988113`) 기준**이다.
> 이미 머지된 항목(#1~#3)은 현재 `main`에서 줄번호가 맞지 않는다 —
> 각 항목에 수정 후 위치를 따로 적어 두었다.

---

## 0. 진행 현황

| 게이트 | 이슈 | 상태 | 브랜치 / PR | 비고 |
|---|---|---|---|---|
| **G0** | #1 인증 기반 정비 | ✅ **머지 완료** | PR #67 ← `fix/#62` | 이슈 본문과 2건 다르게 처리 — §4 이슈 #1 하단 참조 |
| **G1** | #2 히트맵 뒤섞임 | ✅ **머지 완료** | PR #68 ← `fix/#63` | 재현 → 수정 → 재검증 완료. §2 (A)항 참조 |
| **G2** | #3 무인증 API 차단 | ✅ **머지 완료** | PR #69 ← `fix/#64` | 무인증 이미지 15장·히트맵 12장 → 0장. 키오스크는 `session/*` 토큰 경로로 대체, compose에서 fastapi `ports` 제거, `JwtFilter` 예외별 로깅 분리 |
| **G2** | #4 처방자 위조 차단 | ✅ **구현 완료 · PR 대기** | `fix/#65` | 계획에 없던 `doctor-patients` 건도 함께 처리 — §4 이슈 #4 하단 참조 |
| **G2** | #5 타임아웃 + 트랜잭션 | ✅ **구현 완료 · PR 대기** | `fix/#65` | 외부 호출을 트랜잭션 밖으로 분리. 무응답 시 503으로 끊김 — §4 이슈 #5 하단 참조 |
| **G3/G4** | — | ⬜ 착수 전 | — | |

> #1~#3은 `main`에 머지 완료. 아래 §2의 지적 중 ✅ 표시가 없는 항목은 현재 `main`에서 여전히 유효하다.

---

## 1. 이 계획의 전제

- **현재 외부 배포 인스턴스는 없다.** 로컬 `docker-compose`로만 구동 중이다.
- **EC2 배포가 예정되어 있다.** → 배포 시점이 곧 보안 데드라인이다. 아래 G0는 배포 전 반드시 닫아야 한다.
- **저장소는 PUBLIC이다** (`CSID-DGU/2026-1-CECD1-5-Artifact-9`). 소스에 있는 모든 값은 공개된 값으로 취급한다.
- 멘토(비트컴퓨터) 제공 원본 엑셀의 저장소 업로드는 **의도된 공개**이므로 이 계획에서 제외한다.
  (감사 문서 36번 중 엑셀 부분 해당 없음)

### 먼저, 하지 않아도 되는 일

**AWS·DB·Gemini 자격증명 로테이션은 불필요하다.**

근거: `.env`와 `backend/.env`는 git에 **한 번도 추적된 적이 없고**, 히스토리 전체에 AWS 액세스 키 패턴(`AKIA`)이 없다.

```
git log --all --oneline --name-only -- '.env' 'backend/.env'   → 결과 없음
git log --all -S'AKIA' --oneline                                → 결과 없음
```

감사 문서에 언급되지 않은 사항이라 명시해 둔다. "유출됐을지 모른다"는 막연한 불안으로 키를 전부 재발급하는 데
시간을 쓰지 말 것. **단, JWT 서명키는 예외다 — 아래 F항 참조. 그건 유출이 아니라 처음부터 공개된 값이다.**

---

## 2. 검증 로그 — 감사 문서 항목별 판정

각 항목을 `✅ 확인됨` / `🔧 원인 정정` / `❌ 오탐` / `⚠️ 도메인상 부적합` / `🆕 문서에 없던 발견` 으로 분류했다.

### 2-1. 정정이 필요한 항목 (구현 전 반드시 읽을 것)

#### 🔧 (A) FastAPI 동시성 — 결론은 맞고 원인은 틀렸다 · ✅ **수정 완료**

감사 문서: *"전역 `model` 객체에 forward hook을 등록하고 `activation_store`를 공유한다"*

**`activation_store`는 공유되지 않는다.** `fastapi/main.py:64`의 `activation_store = {}`는
`_generate_gradcam_b64()` 호출마다 새로 만들어지는 **지역 변수**다.

실제 원인은 `fastapi/main.py:71`이다:

```python
handle = model.conv_head.register_forward_hook(fwd_hook)   # 전역 model에 등록
```

hook이 **전역 `model` 객체**에 걸리므로, 스레드 A가 hook을 건 상태에서 스레드 B가 forward를 돌리면
**A의 hook이 B의 activation으로 발화**해 A의 지역 딕셔너리를 오염시킨다.
여기에 `main.py:75-76`의 `model.zero_grad()` / `output[0, pred].backward()`가
공유 파라미터의 `.grad`를 동시에 건드린다.

> **⚠️ 이 차이가 처방을 바꾼다.**
> 감사 문서의 "중기 조치 — `contextvars` 또는 요청별 로컬 딕셔너리로 격리"는 **이 버그를 고치지 못한다.**
> 저장 위치가 문제가 아니라 hook 등록과 backward가 전역 객체에 걸리는 것이 문제이기 때문이다.
> 문서가 "즉효 조치"로 격하한 `threading.Lock`이 사실상 유일한 정답이다.

**채택 조치**: 추론 + Grad-CAM 구간 전체를 하나의 `threading.Lock`으로 직렬화한다.

##### 실측 — 위 진단이 맞았음이 확인됐다

수정 전 코드에 서로 다른 이미지 2장을 동시에 20라운드(총 40요청) 보낸 결과:

```
HTTP 실패 13 · 히트맵 내용 불일치 7  →  오염된 응답 20 / 40 (50%)
```

서버 로그의 실제 예외가 위 메커니즘을 그대로 보여준다:

```
RuntimeError: cannot register a hook on a tensor that doesn't require gradient
```

A가 건 hook이 B의 `torch.no_grad()` forward에서 발화했는데, `no_grad` 텐서에는
`register_hook`을 걸 수 없어 **B의 요청이 500으로 죽은 것**이다.
`activation_store`를 요청별로 격리했다면 이 예외는 그대로 남는다 →
**`contextvars` 처방이 무효라는 것이 실증됐다.**

**수정 후 재실행: 오염 0 / 40.** 처리량은 직렬 2.31 → 동시4 2.38 req/s로 **변화 없음**
(CPU 추론은 PyTorch가 이미 코어를 다 쓰므로 요청을 병렬화해도 총량이 늘지 않는다.
즉 이 서버에서 Lock의 처리량 비용은 사실상 0이다. "처리량이 떨어진다"는 당초 예상은 틀렸다).

##### 수정 시 함께 발견한 것 — hook 누수

원본 `main.py:71-77`은 `handle.remove()`가 `try` 블록 **안**에 있었다.
`model(tensor)`가 예외를 던지면 `remove()`가 실행되지 않아 **전역 모델에 hook이 영구히 남는다.**
한 번 실패하면 그 뒤 모든 요청이 계속 오염되는 구조였다. → `finally`로 이동해 수정했다.

**수정 후 위치** (`fix/#63-multiple-heatmap-error-fix`): `_model_lock`은 `main.py:57`,
모델 구간은 `_compute_gradcam()`, 락 밖 후처리는 `_render_gradcam_overlay()`로 분리.

#### ❌ (B) 감사 35번 "devtools가 운영 빌드 포함" — 오탐

`backend/build.gradle:33`은 `developmentOnly` 설정이다.

```gradle
developmentOnly 'org.springframework.boot:spring-boot-devtools'
```

Spring Boot의 `developmentOnly` 구성은 `bootJar` 재패키징에서 **자동 제외**된다. 운영 빌드에 포함되지 않는다.
**조치 불필요. 백로그에서 삭제한다.**

#### ⚠️ (C) 감사 1번 "Visit 소유권 검사" — 도메인상 부적절

감사 문서는 이미지 조회에 "Visit 소유권 검사"를 권한다. **채택하지 않는다.**

실제 병원 EMR에서 **당직 의사는 모든 환자 차트를 열 수 있다.** 의사별 소유권 모델을 넣으면
협진·인수인계·대진·야간 당직이 전부 막혀 제품이 망가진다. 이건 보안 강화가 아니라 기능 파괴다.

의료법이 요구하는 통제는 소유권이 아니라 **역할 기반 접근 + 접속기록(감사 로그)**이다.
누가 열었는지를 *막는* 게 아니라 *기록하는* 것이 규범이다.

**채택 조치**: 소유권 검사 대신 `@PreAuthorize` 역할 검사 + 감사 로그(G3)로 대체한다.

#### ⚠️ (D) 공통 `AccessGuard` AOP 권고 — 비용 과다

감사 문서는 `@PreAuthorize`보다 AOP `AccessGuard` 컴포넌트를 권한다.
4인 팀 기준 하루 이상 들고, 잘못 만들면 조용히 no-op이 되어 **있는 줄 알았는데 없는** 최악의 상태가 된다.

같은 보장을 훨씬 싸게 얻는 방법:

1. `@PreAuthorize`를 컨트롤러 메서드에 전면 적용 (약 1시간)
2. **인가 애노테이션이 없는 컨트롤러 메서드를 발견하면 빌드를 실패시키는 ArchUnit 테스트 1개** (약 1시간)

감사 문서가 우려한 "새 엔드포인트 추가할 때 또 빠뜨릴 위험"은 2번이 동일하게 막아준다.
`SecurityConfig:22`에 `@EnableMethodSecurity`가 **이미 붙어 있어** 별도 설정 없이 바로 동작한다.

#### 🔧 (E) 감사 1번 이미지 IDOR — 결론 유효, 근거 정정

`VisitImageService.java:79`는 이미 `imageId`가 `visitId`에 속하는지 검증한다:

```java
.filter(img -> img.getVisitId().equals(visitId))
```

**단순 IDOR은 아니다.** 다만 두 값 모두 순차 정수이고 엔드포인트가 `permitAll`이라
`(visitId, imageId)` 쌍 열거로 전량 수집이 가능하다. 결론(무인증 다운로드 가능)은 유효하다.

> 미팅 자료에 "순차 ID로 무인증 다운로드"라고만 적으면, 코드를 본 상대가 쌍 검증을 발견했을 때
> 감사 전체의 신뢰도가 흔들린다. **"인증이 없어 쌍 열거로 수집 가능"으로 정확히 적을 것.**

#### 🔧 프론트 `alert()` 위치 정정

감사 문서는 "alert() 사용 2곳 — 키오스크 UX"라 했으나, 실제로는 2곳 모두 `Login.tsx:30`, `Login.tsx:46`이다.
키오스크가 아니라 로그인 화면이다.

### 2-2. 감사 문서에 없던 발견

#### 🆕 (F) 【최상위】 PUBLIC 저장소 + JWT 서명키 하드코딩 기본값이 실사용 중 · ✅ **수정 완료(미머지)**

> **상태**: PR #67로 `main`에 머지 완료. 아래 내용은 **감사 시점 기준**의 기록이다.

세 가지가 동시에 성립한다:

| 사실 | 근거 |
|---|---|
| 저장소가 PUBLIC | `gh repo view` → `"visibility":"PUBLIC"` |
| 서명키 기본값이 공개 소스에 있음 | `application.properties:65` |
| **기본값이 실제로 사용 중** | `JWT_SECRET`이 `.env`·`backend/.env`·`docker-compose.yml` **어디에도 없음** |

```properties
jwt.secret=${JWT_SECRET:artifact-medical-ai-jwt-secret-key-must-be-at-least-256-bits-long}
```

그리고 `JwtFilter.java:32-37`은 토큰의 `role` 클레임을 그대로 신뢰해 권한을 부여한다.

> **EC2에 이 상태로 배포되는 순간, 인터넷의 누구나 `role: ADMIN` 토큰을 위조해 전체 API를 장악한다.**
> 로그인도, 계정도 필요 없다. 공개된 키로 서명하면 끝이다.

**감사 문서는 이를 1단계 5번으로 매겼으나, 이는 순위가 잘못됐다.**
서명키가 공개된 상태에서는 감사 1·2·4·7번(인가 관련) 수정이 **전부 무의미**하다.
인가(authorization)는 신원 확인(authentication) 위에만 성립하기 때문이다.
잠금장치를 늘려도 마스터키가 공개돼 있으면 의미가 없다.

**→ 모든 항목보다 먼저다.**

> **수정 후** (`fix/#62-fix-authentification`): 기본값을 제거해 `jwt.secret=${JWT_SECRET}`으로 바꿨다.
> 값이 없으면 Spring이 `PlaceholderResolutionException`으로 **기동 자체를 실패**시킨다 —
> 기본값을 "안전한 값"으로 바꾸는 것으로는 부족하다. 조용히 뜨는 순간 아무도 눈치채지 못하기 때문이다.
>
> `README.md:502`의 서명키 값과 `README.md:60, 126, 459-464`의 `admin / 1234`도 같은 PR에서 함께 지웠다 —
> 소스에서 키를 빼도 README에 남으면 공개된 것은 마찬가지다.

#### 🆕 (G) 가입 role 자유 지정 — 감사 문서가 심각도를 축소 기술

감사 문서: *"회원가입이 전면 공개 + role 기본값 DOCTOR"*

기본값 문제가 아니다. `MemberSignupRequest.java:12-16`에서 `role`은 **클라이언트가 자유롭게 지정하는 필드**이고,
`resolvedRole()`은 `ADMIN`을 포함해 무엇이든 그대로 통과시킨다:

```java
public MemberRole resolvedRole() {
    return role != null ? role : MemberRole.DOCTOR;   // role="ADMIN" 이면 ADMIN 그대로
}
```

가입은 `SecurityConfig:39`에서 `permitAll`이다.
→ **누구나 ADMIN으로 자가 등록 가능.** 기본값 설정 실수가 아니라 권한 상승 취약점이다.

> **상태**: PR #67로 `main`에 머지 완료.
>
> **수정 방식이 계획과 다르다.** 당초 계획은 "`role` 필드를 DTO에서 삭제하고 서버가 항상 DOCTOR로 강제"였으나,
> 실제로는 **화이트리스트 방식**으로 구현했다 — 필드는 남기되 `DOCTOR`/`NURSE`만 허용하고
> `ADMIN`은 거부한다. 필드를 지우면 간호사 계정을 만들 방법이 사라져 운영이 막히기 때문이다.
> 관리자 계정은 가입 API가 아니라 별도 경로로만 생성한다는 원칙은 동일하게 유지된다.

#### 🆕 (I) `/predict`가 FastAPI 이벤트 루프를 블로킹 · ✅ **수정 완료**

`main.py:155`(감사 시점 기준)는 `async def`인데 동기 함수 `run_inference`를 직접 호출한다.
→ 추론이 도는 동안 **FastAPI 이벤트 루프 전체가 멈춘다.** `/health`조차 응답하지 않는다.
`/predict-base64`의 스레드풀 경합(A항)과는 **별개의 버그**다.

**실측** — 2400×2400 이미지 추론(710ms) 중 `/health`를 50ms 간격으로 폴링:

| | 최악 `/health` 응답 |
|---|---|
| 수정 전 (`async def`) | **674ms** — 추론이 끝날 때까지 통째로 대기 |
| 수정 후 (`def`) | **4–5ms** |

`async def` → `def`로 바꾸면 FastAPI가 알아서 스레드풀로 넘긴다. 한 글자 수정이다.
(`fix/#63-multiple-heatmap-error-fix`에서 A항과 함께 처리)

#### 🆕 (J) 테스트가 실제 스키마를 전혀 검증하지 않는다 — **실제로는 더 나쁘다**

| | 테스트 | 운영 |
|---|---|---|
| DB | H2 (`MODE=MySQL`) | MySQL 8.0 |
| 스키마 생성 | `ddl-auto=create-drop` (JPA 엔티티 기준) | `ddl-auto=none` + `DataInitializer` 수동 DDL |

`DiagnosisApplicationTests.java:30-34` 참조. **두 스키마는 완전히 다른 코드 경로로 만들어진다.**
→ 테스트를 아무리 늘려도 운영 스키마 회귀는 못 잡는다.
감사 문서 4단계의 "테스트 확충"은 **Testcontainers 전환이 선행되지 않으면 효과가 절반 이하**다.

> ### ⛔ 그런데 그 테스트는 **애초에 컴파일되지 않는다**
>
> 감사 시점(`a988113`)에 `./gradlew compileTestJava`를 실행하면 실패했다 — PR #67에서 고쳤다:
>
> ```
> error: constructor PrescriptionRequest in record PrescriptionRequest
>        cannot be applied to given types;
>   required: Long,List<...>,Long,LocalDate,String,String,String,LocalDateTime,Boolean,List<...>
>   found:    Long,List<...>,Long,LocalDate,String,String
> ```
>
> `PrescriptionRequest`에 AI 코멘트 관련 필드 4개(`aiComment`, `aiCommentModel`,
> `aiCommentGeneratedAt`, `aiCommentEdited`)가 추가됐는데 **테스트는 갱신되지 않았다.**
>
> → **실제로 돌아가는 백엔드 테스트는 2개가 아니라 0개다.** 문서 곳곳의 "테스트 2개"는 전부 오기다.
> CI가 없어서(감사 32번) 이 컴파일 실패를 아무도 몰랐다는 점이 더 중요하다 —
> **테스트 부족이 아니라 테스트가 죽어 있는 것을 감지할 장치가 없는 것**이 진짜 문제다.
> EMR 미팅에서 "테스트가 있습니다"라고 말하면 안 되는 이유다.

#### 🆕 (K) 기타

- **`KioskService.java:82-86` 로직 오류**: 루프에 `break`가 없어 마지막 후보를 잡는다.
  리포지토리 메서드가 `findByStatusOrderByVisitDateAsc` — **오래된 순 정렬**이므로
  마지막 원소는 **가장 최근에 접수한 환자**다. 그런데 같은 메서드의 Javadoc/Swagger 설명은
  *"가장 최근"*이라 적혀 있어 정렬 방향과 모순된다.
  → 코드·주석 중 하나는 반드시 틀렸다. 의도가 "가장 오래 기다린 환자"라면 `break` 한 줄이면 되고,
  "가장 최근"이 의도라면 정렬을 `Desc`로 바꿔야 한다. **먼저 어느 쪽이 맞는지 확정할 것.**
  감사 문서의 N+1 지적과는 별개 사안이다.
- **추론 1건당 forward 2회**: `main.py:115`(no_grad)와 `main.py:73`(Grad-CAM 내부)에서
  모델이 두 번 돈다. 지연시간 2배.
  → **A항 수정에서 의도적으로 건드리지 않았다.** 두 forward를 합치려면 `no_grad` 제거와
  출력 재사용이 필요해 리뷰 난이도가 올라간다. 동시성 버그 수정과 성능 최적화를 한 PR에 섞지 않았다.
  별도 이슈로 남긴다.
- **Security CORS 미연동은 잠재 결함**: `SecurityConfig`에 `.cors()`가 없어 `WebConfig`의 CORS 설정이
  Security 필터체인에 반영되지 않는다. 다만 nginx 경유 시 same-origin이라 **현재 배포 경로에서는 안 터진다.**
  감사 34번이 매긴 우선순위보다 낮게 잡아도 된다.

### 2-3. 확인된 항목 (감사 문서 그대로 유효)

> **판정 기준은 감사 시점(`a988113`)이다.** ✅는 "그 시점에 참이었다"는 뜻이고,
> 이후 머지된 항목은 `→ 수정됨(PR)`을 덧붙였다.

<details>
<summary>✅ 1단계 보안 (펼치기)</summary>

| # | 항목 | 근거 위치 | 판정 |
|---|---|---|---|
| 1 | 이미지 무인증 조회 | `SecurityConfig.java:41-42` | ✅ (근거는 E항대로 정정) |
| 2 | 처방 의사를 클라이언트가 지정 | `PrescriptionService.java:37-38` | ✅ |
| 3 | 회원가입 전면 공개 | `SecurityConfig.java:39` | ✅ (심각도는 G항대로 상향) → **수정됨 `fix/#62`** |
| 4 | `@PreAuthorize` 0개 | 전체 검색 결과 0건 | ✅ |
| 5 | JWT 비밀키 하드코딩 | `application.properties:65` | ✅ (F항으로 최상위 승격) → **수정됨 `fix/#62`** |
| 6 | 하드코딩 admin 계정 자동 생성 | `DataInitializer.java:112-123` | ✅ BCrypt 해시가 공개 소스에 노출 → **수정됨 `fix/#62`** |
| 7 | 대기 환자 실명 무인증 노출 | `SecurityConfig.java:45` + `KioskController:27` | ✅ |
| 8 | FastAPI 무인증 + CORS `*` | `docker-compose.yml:44-45`, `main.py:18` | ✅ |
| 9 | JWT 검증 실패를 조용히 삼킴 | `JwtFilter.java:39` `catch (Exception ignored)` | ✅ |

</details>

<details>
<summary>✅ 2단계 의료 소프트웨어 자격 요건 (펼치기)</summary>

| 항목 | 근거 | 판정 |
|---|---|---|
| 감사 추적 전무 | `audit` 관련 테이블·코드 전무 | ✅ 의료법 §22, 개인정보보호법 시행령 §30 |
| 처방 원본 물리 삭제 | `PrescriptionService.java:41-42` `prescriptionRepository::delete` | ✅ |
| 환자 정보 국외 LLM 전송 | `GeminiService.java:62-63` 접수 메모가 프롬프트에 삽입 | ✅ |
| AI 출력 법적 지위 미정의 | `AnalysisService.java:56-64` 임상 권고 문구 하드코딩 | ✅ |
| 처방 후보가 단순 키워드 검색 | `GeminiService.java:42-43` "연고"/"크림" 상위 5개 | ✅ |
| 환자 개인정보 평문 저장 | `Patient.java:28-42` 이름·생년월일·전화 모두 평문 | ✅ |
| 이름 검색 `%` 미이스케이프 | `PatientService.java:61` | ✅ 검색어 `%` 입력 시 전체 명단 덤프 |

</details>

<details>
<summary>✅ 3단계 안정성·인프라 (펼치기)</summary>

| 항목 | 근거 | 판정 |
|---|---|---|
| 트랜잭션 내 외부 HTTP 호출 | `AnalysisService.java:66`, `KioskService.java:132` | ✅ 커넥션 풀 고갈 위험 |
| 타임아웃 부재 | `AnalysisService:172`, `KioskService:240`, `GeminiService:99,187` | ✅ 4곳 (문서는 3곳) |
| `DataInitializer`가 마이그레이션 대행 + 실패 삼킴 | `log.warn` 9곳 | ✅ 스키마 반쯤 깨진 채 정상 부팅 가능 |
| `imageIds.get(0)` 빈 리스트 시 500 | `AnalysisService.java:73` | ✅ |
| `indexOf(item)` O(n²) + 중복 시 rank 오류 | `AnalysisService.java:130` | ✅ |
| N+1 (환자마다 최종내원일) | `PatientService.java:79` | ✅ |
| N+1 (대기 환자마다 exists) | `KioskService.java:82-86` | ✅ + K항 로직 오류 |
| base64 왕복 메모리 3배 | `AnalysisService:169`, `KioskService:237` | ✅ |
| `file.content_type` None → AttributeError | `main.py:157` | ✅ → **수정됨 `fix/#63`** (`(file.content_type or "")` 가드 → 500 대신 400) |
| `torch.load`에 `weights_only=True` 없음 | `main.py:43` | ✅ → **수정됨 `fix/#63`** |
| HikariCP·타임아웃 설정 전무 | `application.properties` | ✅ |
| 목록 조회 페이징 없음 | `VisitService:69,78,87`, `PatientService` | ✅ |
| 23 HTTPS 없음 | `nginx.conf:2` `listen 80` | ✅ 병원 반입 불가 항목 |
| 24 헬스체크 없음 | actuator 미포함, compose에 backend/fastapi healthcheck 없음 | ✅ |
| 25 MySQL 3306 호스트 노출 | `docker-compose.yml:20-21` | ✅ |
| 26 컨테이너 전부 root | 3개 Dockerfile 모두 `USER` 지시자 없음 | ✅ |
| 27 리소스 제한 없음 | `deploy.resources` 없음 | ✅ |
| 28 백업·복구 전략 없음 | 관련 설정 전무 | ✅ EMR 미팅 필수 질문 |
| 29 nginx 보안 헤더 전무 | `nginx.conf` HSTS/X-Frame-Options/CSP 없음 | ✅ |
| 30 nginx가 JS/CSS까지 no-store | `nginx.conf:14-17` | ✅ |
| 31 환경 분리 없음 | `application.properties` 단일 파일 | ✅ |
| 32 CI/CD 없음 | `.github/workflows/` 디렉터리 자체가 없음 | ✅ |
| 33 로그 수집·모니터링 없음 | 구조화 로깅·알림 전무 | ✅ |
| 34 Security에 CORS 미연동 | `SecurityConfig`에 `.cors()` 없음 | ✅ (K항대로 우선순위 하향) |
| 35 devtools 운영 빌드 포함 | — | ❌ **오탐 (B항)** |
| 36 16MB 모델 git 커밋 | `fastapi/model.pth` 추적 중 | ✅ 모델만 해당. 엑셀은 의도된 공개로 제외 |

</details>

<details>
<summary>✅ 4단계 프론트엔드 (펼치기)</summary>

| 항목 | 근거 | 판정 |
|---|---|---|
| JWT localStorage 저장 | `AuthContext.tsx:50` | ✅ XSS 시 탈취 |
| 401 전역 처리 없음 | `client.ts` | ✅ |
| PrivateRoute가 role 미확인 | `PrivateRoute.tsx:6` 로그인 여부만 확인 | ✅ |
| 토큰 갱신 없음 | `jwt.expiration-ms` 24시간 고정 | ✅ |
| `Clinic.tsx` 단일 컴포넌트 | 1,101줄 | ✅ |
| 요청 타임아웃·재시도·취소 없음 | `client.ts:27` | ✅ |
| ErrorBoundary 없음 | 전체 검색 0건 | ✅ |
| `alert()` 2곳 | `Login.tsx:30,46` | ✅ (위치는 정정 — 키오스크 아님) |
| ~~백엔드 테스트 2개~~ / 프론트 0개 | `DiagnosisApplicationTests.java` | 🔧 **정정: 백엔드도 0개** — `compileTestJava` 자체가 실패한다 (J항) |

</details>

---

## 3. 재정렬한 우선순위 — EC2 배포 게이트 기준

감사 문서의 4단계 10주 계획은 코드를 **이미 병원에 배포된 시스템처럼** 다뤄 산출된 것이다.
실제 제약은 캡스톤 일정과 4인(백엔드+AI는 2인) 체제이므로, **"무엇이 배포를 막는가"** 기준으로 재정렬한다.

### G0 — EC2 배포 전 필수 · 예상 반나절 → ✅ **구현 완료 (PR 대기)**

> 이 게이트를 닫지 않은 채 EC2에 올리면 배포 즉시 공개 장악 상태가 된다.

브랜치: `fix/#62-fix-authentification` → **PR #67로 `main`에 머지 완료.**

| 항목 | 조치 | 파일 | 상태 |
|---|---|---|---|
| **(F) JWT 서명키** | `${JWT_SECRET}` 기본값 제거 → 미설정 시 **기동 실패(fail-fast)**. EC2에는 SSM Parameter Store 또는 환경변수로 주입 | `application.properties:65` | ✅ |
| **(G) 가입 role 승격** | ~~`role` 필드 제거~~ → **화이트리스트로 변경** (아래 참조) | `MemberSignupRequest.java`, `MemberService.java` | ✅ |
| 감사 6번 admin 계정 | `DataInitializer`의 하드코딩 BCrypt 해시 제거 → 환경변수 주입 | `DataInitializer.java:112-123` | ✅ |
| 회원가입 공개 차단 | `/api/v1/auth/signup`을 `permitAll`에서 제외하거나 ADMIN 초대제로 전환 | `SecurityConfig.java:39` | ✅ |

**검증 방법**: `JWT_SECRET` 없이 기동 → 실패해야 정상. 기존 발급 토큰이 전부 무효화되는지 확인(키 교체 효과).

> **계획과 다르게 처리한 것 2건** — 리뷰 시 이 부분을 먼저 볼 것:
>
> 1. **`role` 필드를 지우지 않고 화이트리스트로 막았다.** 필드를 제거하면 `NURSE` 계정을
>    만들 방법이 사라진다. `DOCTOR`/`NURSE`만 허용하고 `ADMIN`은 거부하는 쪽이
>    같은 보안 효과를 내면서 운영을 막지 않는다.
> 2. **`README.md`의 서명키·`admin/1234` 노출도 함께 제거했다.** 계획서에는 없던 항목인데,
>    소스에서 키를 빼도 README에 남아 있으면 아무 의미가 없다.

### G1 — 데모 전 필수 · 예상 1일 → ✅ **구현 완료 (미커밋)**

> 정상적인 동시 사용만으로 재현된다. 심사위원 2명이 동시에 체험하면 그 자리에서 터진다.

브랜치: `fix/#63-multiple-heatmap-error-fix`

| 항목 | 조치 | 파일(감사 시점 기준) | 상태 |
|---|---|---|---|
| **(A) Grad-CAM 히트맵 뒤섞임** | 추론+Grad-CAM 구간을 `threading.Lock`으로 직렬화. **`contextvars`로는 안 고쳐진다 — §2 A항 참조** | `main.py:58-142` | ✅ |
| **(I) 이벤트 루프 블로킹** | `/predict`를 `async def` → `def`로 변경(FastAPI가 스레드풀로 위임) | `main.py:155` | ✅ |
| `file.content_type` None | `(file.content_type or "").startswith("image/")` | `main.py:157` | ✅ |
| `torch.load` | `weights_only=True` 추가 | `main.py:43` | ✅ |
| *(계획 외)* hook 누수 | `handle.remove()`를 `try` 안 → `finally`로 이동 | `main.py:71-77` | ✅ |

**검증 방법**: 서로 다른 두 이미지를 동시에 20회 POST → 각 응답의 히트맵이 자기 입력과 일치하는지 확인.
Lock 적용 전에 재현 테스트를 먼저 작성해 **실패하는 것을 확인한 뒤** 고칠 것.

**실제 검증 결과** (`fastapi/tests/test_concurrent_heatmap.py`):

| | 수정 전 | 수정 후 |
|---|---|---|
| 동시 40요청 중 오염 | **20건 (50%)** | **0건** |
| 추론 중 `/health` 최악 응답 | 674ms | 4–5ms |
| Content-Type 없는 업로드 | 500 | 400 |
| 처리량(2400px 동시4) | 측정 불가(33% 실패) | 2.01 req/s |

> **계획과 다르게 처리한 것 1건**: Lock 범위를 `run_inference` 전체가 아니라
> **모델을 만지는 구간에만** 걸고, 히트맵 렌더링(이미지 리사이즈·JPEG 인코딩)은 락 밖으로 뺐다.
> 2400px 이미지 동시 4요청 기준 처리량 1.90 → 2.01 req/s(약 6%). 차이가 크지 않으므로
> 리뷰에서 복잡하다고 판단되면 전체를 감싸는 쪽으로 되돌려도 무방하다.

### G2 — EMR 미팅 전 권장 · 예상 3~5일

| 항목 | 조치 | 상태 |
|---|---|---|
| 감사 1·7번 무인증 엔드포인트 | 이미지/히트맵/키오스크의 `permitAll` 해제. 키오스크는 접수 시 발급하는 **스코프 제한 게스트 토큰**으로 (`kioskToken` 설계가 이미 있음 — 조회 엔드포인트만 마무리) | ✅ 이슈 #3 |
| 감사 2번 처방자 위조 | `@AuthenticationPrincipal`에서 처방자 획득, `PrescriptionRequest`에서 `memberId` 제거 | ✅ 이슈 #4 |
| 감사 4번 인가 미강제 | **(D)항 채택**: `@PreAuthorize` 전면 적용 + 애노테이션 누락 시 빌드 실패시키는 ArchUnit 테스트 1개. **소유권 검사는 넣지 않는다 — (C)항 참조** | ⬜ 남음 |
| 감사 8번 FastAPI 노출 | compose에서 `ports` 제거(내부 통신만) + 백엔드↔FastAPI 공유 시크릿 헤더 | ◐ 포트 제거만 완료(이슈 #3). **공유 시크릿 헤더 남음** |
| 감사 9번 JWT 예외 삼킴 | 예외 종류별 로깅 분리, 인증 실패 이벤트 기록 | ✅ 이슈 #3 |
| 타임아웃 4곳 | `HttpClient`에 `connectTimeout`/`timeout` 지정 + **싱글톤 빈으로 등록**해 커넥션 풀 재사용 | ✅ 이슈 #5 |
| 트랜잭션 경계 | 외부 호출(FastAPI/S3/Gemini)을 트랜잭션 **밖**으로, 결과 저장만 짧은 `@Transactional`로 | ✅ 이슈 #5 |
| `PatientService.java:61` | `%`·`_` 이스케이프 처리 | ⬜ 남음 |

> 타임아웃 + 트랜잭션 경계 + `HttpClient` 싱글톤은 **하나의 리팩토링으로 함께** 처리하는 것이 효율적이다.
> 코드량 대비 임팩트가 가장 크므로 G2에서 먼저 손댈 것.
> → 이슈 #5에서 예정대로 한 묶음으로 처리했다(§4 이슈 #5 하단).

### G3 — 미팅에서 "계획"으로 제시 · 착수만

> 완성보다 **설계가 존재한다는 증거**가 미팅에서 더 유효하다. 스켈레톤 + 설계 문서까지만.

| 항목 | 조치 |
|---|---|
| 감사 추적 | Spring AOP `@Around` + `@Auditable` 애노테이션 → `audit_log(actor_id, action, resource_type, resource_id, timestamp, ip)`. (C)항에서 소유권 검사를 뺀 만큼 **이것이 규범적 통제의 본체**가 된다 |
| 처방 원본 보존 | `prescription`에 `version`/`superseded_by`/`is_active` 추가. 물리 삭제 → `is_active=false` + 새 버전 insert |
| AI 출력 고지 | **API 응답 스키마에 `disclaimer` 필드를 필수로 박아 넣는다.** 프론트가 실수로 누락할 방법을 없앤다 |
| PII 마스킹 | Gemini 전송 전 접수 메모에서 이름·전화번호 등 식별자 마스킹 (완전한 해법은 아니나 최소 방어선) |

### G4 — 이후

Flyway(**Testcontainers 전환 선행 — (J)항**), HTTPS/TLS 종단, 헬스체크(actuator), MySQL 포트 비공개,
컨테이너 비특권 사용자, 리소스 제한, 백업·복구 리허설, nginx 보안 헤더, 해시 파일명 기준 캐시 전략,
Spring Profile 분리, CI(GitHub Actions), 구조화 로깅, 페이징, 프론트엔드 P2 전반.

> **인프라 착수 순서**: CI(감사 32번)를 **가장 먼저** 세팅해야 이후 G0~G2 수정 PR이
> 최소한의 자동 검증을 거쳐 머지된다. 그다음 헬스체크 → HTTPS. Terraform IaC는 이 모든 게 안정된 다음이어도 늦지 않다.
> 지금 IaC부터 손대면 정작 급한 G0가 밀린다.

### 감사 문서와의 핵심 차이

문서는 "1단계 9개 항목"을 한 덩어리로 묶었다. 그러나 그중 **(F)(G)와 나머지는 선후 의존 관계**다.
신원이 위조 가능한 상태에서 인가를 붙이는 것은 순서가 뒤집힌 작업이며, 먼저 한 일이 헛수고가 된다.

또한 문서의 10주 순차 계획은 EMR 미팅에 필요한 것을 과대평가하고 있다.
**미팅이 요구하는 것은 코드가 완성된 상태가 아니라 — (a) 지혈이 끝났고, (b) 신뢰할 만한 개선 계획이 있고,
(c) 데모가 안 터지는 것**이다. 그건 G0+G1, 약 1.5일이다.

---

## 4. GitHub Issue 초안 (복사해서 바로 사용)

> 팀 이슈 템플릿(`.github/ISSUE_TEMPLATE/이슈-생성.md`) 형식에 맞춰 작성했다.
> 아래 블록을 그대로 복사해 붙여넣으면 된다.
>
> **이슈 개수를 파일 단위로 묶은 이유**: G0의 4가지 조치는 전부 `SecurityConfig` /
> `MemberService` / `DataInitializer` 같은 **같은 파일들을 건드린다.** 이슈를 잘게 쪼개
> 브랜치를 4개 파면 서로 충돌만 난다. **1 이슈 = 1 브랜치 = 1 PR**로 가는 게 맞다.
>
> 반면 #2(FastAPI)는 `fastapi/main.py`만 건드려 #1과 겹치지 않으므로 **동시 진행 가능**하다.

**진행 순서**

| 순서 | 이슈 | 담당 영역 | 실제 브랜치 | 예상 | 상태 |
|---|---|---|---|---|---|
| 1 | #1 인증 기반 정비 | BE | `fix/#62-fix-authentification` | 반나절 | ✅ **완료 · PR 대기** |
| 1 | #2 히트맵 뒤섞임 | AI 서버 | `fix/#63-multiple-heatmap-error-fix` | 1일 | ✅ **완료 · 미커밋** |
| 2 | #3 무인증 API 차단 | BE | — | 1~2일 | ⬜ |
| 2 | #4 처방자 위조 차단 | BE | — | 반나절 | ⬜ |
| 3 | #5 타임아웃 + 트랜잭션 | BE | — | 1일 | ⬜ |

- **#1과 #2는 동시에** 진행해도 충돌 없음 (건드리는 파일이 다름)
- **#3은 #1이 머지된 뒤에** 시작 (같은 `SecurityConfig`를 건드림)
- **`dev`는 사용하지 않는다.** 이슈별 `fix/#NN` 브랜치에서 작업하고 **PR은 `main`으로** 올린다

> 아래 이슈 본문의 `파일:줄번호`는 전부 **감사 시점(`a988113`) 기준**이다.
> #1·#2는 이미 수정되었으므로 각 브랜치에서는 줄번호가 맞지 않는다.

---

### 이슈 #1 — 인증 기반 정비 (최우선) · ✅ 구현 완료

> **구현 결과는 이 이슈 본문 아래 "실제 구현과의 차이" 참조.** 이슈 본문은 등록 당시 원문 그대로 둔다.

**제목**
```
[Fix] [BE] 로그인 없이 관리자 권한을 얻을 수 있는 문제 수정
```

**본문**
````markdown
## 📝 Description

EC2 배포 전에 반드시 막아야 하는 문제 3가지를 한 번에 수정합니다.
전부 인증(로그인) 관련이고 같은 파일들을 건드려서 하나의 이슈로 묶었습니다.

### 문제 1. JWT 서명키가 공개 저장소에 그대로 있고, 실제로 그 값을 쓰고 있음

`application.properties:65`

```properties
jwt.secret=${JWT_SECRET:artifact-medical-ai-jwt-secret-key-must-be-at-least-256-bits-long}
```

`${JWT_SECRET:...}`은 "환경변수가 있으면 그걸 쓰고, **없으면 뒤의 기본값**을 쓴다"는 뜻입니다.
그런데 `JWT_SECRET`이 `.env`, `backend/.env`, `docker-compose.yml` **어디에도 없습니다.**
→ 지금 저 기본값이 실제 서명키로 쓰이고 있습니다.

우리 저장소는 **공개(Public)** 라서, 누구나 GitHub에서 저 키를 읽을 수 있습니다.
JWT는 "키를 아는 사람은 누구나 토큰을 만들 수 있는" 구조라서,
**EC2에 배포되는 순간 아무나 관리자 토큰을 만들어 전체 API를 사용할 수 있습니다.**
로그인도, 계정도 필요 없습니다.

### 문제 2. 회원가입할 때 자기 권한을 직접 정할 수 있음

`MemberSignupRequest.java:14-16`

```java
public MemberRole resolvedRole() {
    return role != null ? role : MemberRole.DOCTOR;   // role을 보내면 그대로 통과
}
```

`role`은 클라이언트가 보내는 값입니다. 가입 API는 `permitAll`(누구나 호출 가능)이고요.
즉 회원가입 요청에 `"role": "ADMIN"`을 넣으면 **그대로 관리자 계정이 만들어집니다.**

### 문제 3. 관리자 계정 비밀번호 해시가 소스코드에 박혀 있음

`DataInitializer.java:112-123`에 admin 계정의 BCrypt 해시가 하드코딩되어 있고,
서버가 켜질 때마다 자동 생성됩니다. 공개 저장소라 해시도 함께 공개된 상태입니다.

---

## ✅ To-Do List

**문제 1 — JWT 키**
- [ ] `application.properties`에서 `jwt.secret`의 기본값 제거 → `jwt.secret=${JWT_SECRET}`
- [ ] `JWT_SECRET`이 없으면 **서버가 아예 안 켜지도록** 확인 (기본값을 지우면 Spring이 자동으로 기동 실패시킴)
- [ ] 로컬용 `JWT_SECRET`을 `.env`에 추가 (256bit 이상 랜덤값, `openssl rand -base64 48`)
- [ ] `docker-compose.yml`의 backend `environment`에 `JWT_SECRET: ${JWT_SECRET}` 추가
- [ ] `.env.example`에 `JWT_SECRET=` 항목 추가 (팀원들이 알 수 있게)

**문제 2 — 가입 권한**
- [ ] `MemberSignupRequest`에서 `role` 필드 삭제
- [ ] `MemberService.signup()`에서 `MemberRole.DOCTOR` 고정
- [ ] 프론트 회원가입 요청에서 `role` 전송 제거 (`AuthContext.tsx`의 `SignupPayload`)

**문제 3 — admin 계정**
- [ ] `DataInitializer`에서 admin 계정 INSERT 구문 삭제
- [ ] 초기 관리자 계정 생성용 SQL을 `docker/mysql/init/`에 별도 파일로 분리하거나, 수동 생성 절차를 README에 기록

**검증**
- [ ] `JWT_SECRET` 없이 서버 실행 → **기동 실패하면 정상**
- [ ] `JWT_SECRET` 넣고 실행 → 로그인 정상 동작 확인
- [ ] 회원가입 요청에 `"role": "ADMIN"`을 넣어도 DOCTOR로 저장되는지 확인
- [ ] 기존에 발급받았던 토큰이 더 이상 안 먹히는지 확인 (키가 바뀌었으므로 정상 동작)

## 참고

`docs/security-remediation-plan.md` §2 (F)(G)항, §3 G0
````

#### 실제 구현과의 차이 (`fix/#62-fix-authentification`)

| 이슈 본문 | 실제 구현 | 이유 |
|---|---|---|
| `MemberSignupRequest`에서 `role` 필드 **삭제** | 필드 유지 + **`DOCTOR`/`NURSE` 화이트리스트**, `ADMIN` 거부 | 필드를 지우면 간호사 계정을 만들 수단이 사라진다. 권한 상승은 동일하게 막힌다 |
| (본문에 없음) | **`README.md`의 JWT 서명키·`admin/1234` 제거** | 소스에서 키를 빼도 README에 남으면 무의미 |

나머지 To-Do는 본문대로 처리했다 (`jwt.secret=${JWT_SECRET}` 기본값 제거,
`.env.example` 추가, `DataInitializer` admin 해시 환경변수화, 프론트 `role` 전송 제거).

---

### 이슈 #2 — 히트맵 뒤섞임 (데모 전 필수) · ✅ 구현 완료

**제목**
```
[Fix] [Other] 동시에 2명이 촬영하면 다른 환자의 히트맵이 저장되는 버그
```

**본문**
````markdown
## 📝 Description

### 어떤 버그인가요

**태블릿 2대에서 동시에 촬영하면, A 환자의 결과에 B 환자의 히트맵이 저장될 수 있습니다.**

해킹이 아니라 **정상적인 동시 사용만으로 재현됩니다.**
데모 때 심사위원 두 분이 동시에 체험하면 그 자리에서 터질 수 있어서 우선순위를 높게 잡았습니다.

### 왜 이런 일이 생기나요

`fastapi/main.py:71`

```python
handle = model.conv_head.register_forward_hook(fwd_hook)
```

Grad-CAM은 "모델이 어디를 보고 판단했는지" 알아내려고 **모델에 도청 장치(hook)를 붙이는**
방식으로 동작합니다. 그런데 `model`은 서버 전체가 **하나만 공유하는 전역 객체**입니다.

그래서 이런 일이 생깁니다:

```
스레드 A: 모델에 hook 붙임 → A 이미지 추론 시작
스레드 B:                     ↳ 이 사이에 B 이미지 추론 시작
                              ↳ A가 붙인 hook이 B 이미지에도 반응함!
스레드 A: hook이 받아온 데이터로 히트맵 생성 → 사실은 B 이미지 데이터
```

`model.zero_grad()`와 `backward()`도 같은 전역 모델의 값을 동시에 건드려서 함께 꼬입니다.

> ⚠️ **주의 — 감사 문서의 설명이 틀렸습니다**
>
> 원본 감사 문서는 "전역 `activation_store`를 공유해서 생기는 문제"라고 적었는데,
> `main.py:64`의 `activation_store = {}`는 **함수 안에서 매번 새로 만들어지는 지역 변수**라
> 공유되지 않습니다.
>
> 그래서 문서가 권한 **`contextvars`로 격리하는 방법은 이 버그를 못 고칩니다.**
> 저장 위치가 문제가 아니라 hook과 backward가 전역 모델에 걸리는 게 문제이기 때문입니다.
> → **`threading.Lock`으로 한 번에 한 요청만 처리하도록 하는 것이 맞는 해법입니다.**

속도는 조금 느려지지만, 태블릿 2~3대 규모에서는 체감되지 않고 **정확성이 우선**입니다.

### 같이 고칠 작은 것들

- `main.py:155` — `/predict`가 `async def`인데 안에서 동기 함수를 호출해서, 추론하는 동안
  **FastAPI 서버 전체가 멈춥니다** (`/health`도 응답 안 함). `async`를 떼면 해결됩니다.
- `main.py:157` — `file.content_type`이 `None`이면 `.startswith()`에서 에러가 납니다.
- `main.py:43` — `torch.load()`에 `weights_only=True`가 없습니다 (PyTorch 권장 보안 옵션).

---

## ✅ To-Do List

**먼저 버그 재현부터**
- [ ] 서로 다른 두 이미지를 동시에 반복 요청하는 테스트 스크립트 작성
- [ ] **고치기 전에 실행해서 실제로 히트맵이 섞이는 것을 확인** (안 섞이면 원인 재조사 필요)

**수정**
- [ ] `main.py` 상단에 `import threading` + `_inference_lock = threading.Lock()` 추가
- [ ] `run_inference()` 전체를 `with _inference_lock:` 으로 감싸기
      (추론과 Grad-CAM을 **둘 다** 포함해야 함 — 하나만 감싸면 안 고쳐짐)
- [ ] `/predict`의 `async def` → `def` 로 변경
- [ ] `file.content_type` → `(file.content_type or "")` 로 변경
- [ ] `torch.load(..., weights_only=True)` 추가

**검증**
- [ ] 재현 스크립트 재실행 → 20회 모두 자기 입력과 일치하는 히트맵이 오는지 확인
- [ ] 키오스크 화면에서 정상 동작 확인 (응답 시간이 크게 늘지 않았는지도 체크)

## 참고

`docs/security-remediation-plan.md` §2 (A)(I)항, §3 G1
````

#### 실제 구현과의 차이 (`fix/#63-multiple-heatmap-error-fix`)

| 이슈 본문 | 실제 구현 | 이유 |
|---|---|---|
| `run_inference()` **전체**를 락으로 감싸기 | **모델을 만지는 구간만** 락 (`_compute_gradcam`), 히트맵 렌더링(`_render_gradcam_overlay`)은 락 밖 | 렌더링은 모델과 무관한 순수 후처리다. 원본 해상도가 큰 키오스크 사진일수록 이 구간이 길어져, 락 안에 두면 그만큼 다른 요청이 통째로 대기한다. 2400px 동시4 기준 1.90 → 2.01 req/s |
| (본문에 없음) | **hook 누수 수정** — `handle.remove()`를 `finally`로 이동 | 원래는 `try` 안에 있어서, forward가 한 번 실패하면 전역 모델에 hook이 영구히 남아 **이후 모든 요청이 계속 오염**된다 |
| (본문에 없음) | forward 2회 문제는 **의도적으로 안 고침** | 동시성 버그 수정과 성능 최적화를 한 PR에 섞지 않기 위해. 별도 이슈로 남긴다 |

재현 스크립트는 `fastapi/tests/test_concurrent_heatmap.py`로 커밋했다.
수정 전 **40요청 중 20건 오염(50%)** → 수정 후 **0건**.
서버 로그에 `RuntimeError: cannot register a hook on a tensor that doesn't require gradient`가
13번 찍힌 것이 위 진단(전역 모델 hook)이 맞았다는 직접 증거다.

---

### 이슈 #3 — 무인증 API 차단

**제목**
```
[Fix] [BE] 로그인 없이 환자 사진·이름을 볼 수 있는 API 차단
```

**본문**
````markdown
## 📝 Description

`SecurityConfig.java:41-45`에서 아래 API들이 `permitAll`(로그인 없이 접근 가능)로 열려 있습니다.

```java
.requestMatchers("/api/v1/visits/*/images/*/content").permitAll()   // 환자 병변 사진
.requestMatchers("/api/v1/visits/*/analysis/heatmap").permitAll()    // 분석 히트맵
.requestMatchers("/api/kiosk/**").permitAll()                        // 대기 환자 실명 목록
```

`visitId`와 `imageId`가 **1, 2, 3... 순차 번호**라서, 숫자만 바꿔가며 요청하면
**전체 환자의 병변 사진을 로그인 없이 받아갈 수 있습니다.**

> 참고로 `VisitImageService.java:79`에 `imageId`가 해당 `visitId`의 것인지 확인하는 코드는
> 이미 있습니다. 다만 두 숫자 모두 순차라 조합을 훑으면 그만이라, 인증이 없는 게 핵심 문제입니다.

키오스크(`/api/kiosk/**`)는 태블릿에 JWT가 없어서 열어둔 것이라 사정이 다릅니다.
다행히 **`kioskToken`(base62 12자리 랜덤) 설계가 이미 되어 있어서**, 이걸 조회 API에도
적용하면 됩니다. 새로 만들 게 아니라 있는 걸 마저 연결하는 작업입니다.

## ✅ To-Do List

- [ ] `SecurityConfig`에서 이미지/히트맵의 `permitAll` 제거 → 로그인 필수로 변경
- [ ] 프론트에서 이미지 요청 시 `Authorization` 헤더가 붙는지 확인
      (`<img src>` 태그는 헤더를 못 붙이므로, blob 방식으로 바꿔야 할 수 있음 — FE 협의 필요)
- [ ] `/api/kiosk/pending`(대기 환자 실명 노출) 처리 방침 결정 후 적용
- [ ] `/api/kiosk/preliminary/{visitId}/heatmap`을 `visitId` 대신 `kioskToken` 기반으로 변경
- [ ] `docker-compose.yml`에서 fastapi의 `ports: 8000:8000` 제거 (컨테이너 내부 통신만)
- [ ] `JwtFilter.java:39`의 `catch (Exception ignored)` → 예외 종류별 로그 남기기

**검증**
- [ ] 토큰 없이 이미지 URL 직접 호출 → 401 나오는지 확인
- [ ] 로그인 상태의 진료 화면에서 이미지·히트맵이 정상 표시되는지 확인
- [ ] 키오스크 QR 흐름이 그대로 동작하는지 확인

## 참고

`docs/security-remediation-plan.md` §2 (E)항, §3 G2

> ⚠️ 이슈 #1이 머지된 뒤에 시작하세요. 같은 `SecurityConfig.java`를 건드려서 충돌납니다.
````

---

### 이슈 #4 — 처방 의사 위조 차단 · ✅ 구현 완료

**제목**
```
[Fix] [BE] 처방한 의사를 클라이언트가 지정하는 문제 수정
```

**본문**
````markdown
## 📝 Description

`PrescriptionService.java:37-38`

```java
Member member = memberRepository.findById(req.memberId())   // 요청 body의 memberId를 그대로 신뢰
```

처방을 저장할 때 **"누가 처방했는지"를 클라이언트가 보내는 값으로 결정**하고 있습니다.
즉 A 의사가 로그인한 상태에서 `memberId`만 B 의사 번호로 바꿔 보내면,
**B 의사 이름으로 처방 기록이 남습니다.**

진료기록 위조에 해당하는 문제라, 로그인한 사용자 정보에서 직접 가져와야 합니다.
JWT에 이미 `memberId` 클레임이 들어 있어서(`JwtUtil.java:34`) 어렵지 않습니다.

## ✅ To-Do List

- [ ] `JwtFilter`에서 인증 객체에 `memberId`를 담도록 수정 (현재는 `loginId`만 담고 있음)
- [ ] `PrescriptionController.save()`에 `@AuthenticationPrincipal` 파라미터 추가
- [ ] `PrescriptionService.save()`가 요청 body 대신 인증 정보의 `memberId`를 사용하도록 변경
- [ ] `PrescriptionRequest`에서 `memberId` 필드 삭제
- [ ] 프론트에서 `memberId` 전송 제거 (`api/prescription.ts`)
- [ ] 기존 테스트(`DiagnosisApplicationTests`) 수정

**검증**
- [ ] 로그인한 의사로 처방 저장 → `prescription.member_id`가 로그인 계정과 일치하는지 확인
- [ ] 요청 body에 다른 `memberId`를 넣어도 무시되는지 확인

## 참고

`docs/security-remediation-plan.md` §3 G2 (감사 문서 2번)
````

#### 실제 구현 (`fix/#65`)

To-Do 6건은 본문대로 처리했고, 그 과정에서 **본문에 없던 같은 유형 1건**을 추가로 고쳤다.

| 항목 | 처리 |
|---|---|
| 신원 배선 | `AuthPrincipal(memberId, loginId, role)` 레코드를 새로 두고 `JwtFilter`가 이걸 principal로 심는다. `java.security.Principal`을 구현해 `Authentication.getName()`은 기존대로 loginId를 돌려준다 |
| `memberId` 클레임 없는 토큰 | 인증을 심지 않고 통과 → 403. 구버전 토큰으로 신원 미상 요청이 들어오는 걸 막는다 |
| 처방 저장 | `PrescriptionController.save(visitId, @AuthenticationPrincipal, body)` → `PrescriptionService.save(visitId, doctorId, req)`. body의 `memberId` 필드는 삭제 |
| **(추가)** `GET /api/v1/prescriptions/doctor-patients` | `@RequestParam Long doctorId`를 **제거**하고 인증 신원으로 대체. 값만 바꿔가며 다른 의사의 담당 환자 명단(환자 실명 포함)을 순회할 수 있었다. 프론트에서 호출하지 않는 엔드포인트라 영향 없음 |

> 📌 **`doctor-patients`를 "소유권 검사"로 오해하지 말 것.** §2 (C)에서 정리했듯 EMR에서
> 다른 의사의 차트 열람은 정상 동작이라 소유권 모델을 넣으면 안 된다. 여기서 막은 건 열람 범위가 아니라
> **"내 목록"을 요청자가 아닌 값으로 지정할 수 있었던 것**이다. 조회 대상이 화면 요구사항상 항상 본인이므로
> 파라미터 자체를 없앤 것이지, 협진·인수인계용 타 의사 차트 열람을 막은 게 아니다.

**검증 결과** (로컬 도커 실서버 + `./gradlew test`)

| 확인 항목 | 결과 |
|---|---|
| A 토큰 + body에 B의 `memberId` → 저장 | `prescription.member_id = 6`(A). B(7)는 무시됨 |
| 토큰 없이 처방 저장 | 403 |
| A 토큰 + `doctorId=B` → 조회 | A 본인 처방 1건만 반환 |
| B 토큰 + `doctorId=A` → 조회 | 0건 |
| Swagger `PrescriptionRequest` 스키마 | `memberId` 사라짐 |
| `./gradlew test` | 6/6 통과 (위조 시도 테스트 2건 신규) |

---

### 이슈 #5 — 타임아웃 + 트랜잭션 경계 · ✅ 구현 완료

**제목**
```
[Fix] [BE] 외부 API 응답이 없으면 서버 전체가 멈추는 문제 수정
```

**본문**
````markdown
## 📝 Description

### 문제 1. 타임아웃이 한 곳도 없음

FastAPI·Gemini를 호출하는 4곳 모두 `HttpClient`에 타임아웃 설정이 없습니다.

- `AnalysisService.java:172`
- `KioskService.java:240`
- `GeminiService.java:99`, `GeminiService.java:187`

→ FastAPI나 Gemini가 응답하지 않으면 **해당 스레드가 영원히 기다립니다.**
이런 요청이 쌓이면 서버가 응답을 멈춥니다.

### 문제 2. DB 트랜잭션 안에서 외부 API를 호출함

`AnalysisService.analyze()`와 `KioskService.analyze()`는 `@Transactional` 안에서
**FastAPI 추론 + S3 업로드 + Gemini 호출**을 전부 합니다.

DB 커넥션은 개수가 정해져 있는 자원인데(기본 10개), 이걸 **수 초씩 붙잡고 있는** 셈입니다.
동시 사용자가 조금만 늘어도 커넥션이 바닥나서 **전체 API가 멈춥니다.**

### 문제 3. `HttpClient`를 매 요청마다 새로 만듦

`HttpClient.newBuilder()`가 요청마다 호출되어 커넥션 재사용이 안 됩니다.

→ 이 3가지는 **같이 고치는 게 효율적**이라 하나로 묶었습니다.

## ✅ To-Do List

- [ ] `HttpClient`를 `@Bean`으로 등록 (`connectTimeout` 5초 등)
- [ ] `AnalysisService` / `KioskService` / `GeminiService`가 주입받아 쓰도록 변경
- [ ] 각 `HttpRequest`에 `.timeout(Duration.ofSeconds(30))` 지정
      (추론은 시간이 걸리므로 Gemini보다 넉넉하게)
- [ ] `analyze()` 구조 변경: 외부 호출(FastAPI/S3/Gemini)을 트랜잭션 **밖**으로 빼고,
      결과 저장만 짧은 `@Transactional` 메서드로 분리
- [ ] `application.properties`에 HikariCP 설정 추가 (`connection-timeout`, `maximum-pool-size`)
- [ ] `AnalysisService.java:73` — `imageIds`가 비어 있으면 500 나는 문제도 함께 처리

**검증**
- [ ] FastAPI 컨테이너를 정지시킨 상태에서 분석 요청 → 30초 안에 에러 응답이 오는지 확인
      (지금은 무한 대기)
- [ ] 정상 분석 흐름이 그대로 동작하는지 확인

## 참고

`docs/security-remediation-plan.md` §3 G2
````

#### 실제 구현 (`fix/#65`)

To-Do 6건 모두 본문대로 처리했다. 다만 **트랜잭션 분리 방식은 본문보다 한 겹 더 들어가야 했다.**

| 항목 | 처리 |
|---|---|
| 공용 `HttpClient` | `HttpClientConfig`에 `@Bean` 1개. `connectTimeout` 5초, HTTP/1.1 고정(uvicorn이 평문 HTTP/2를 받지 않는다). `AnalysisService`·`KioskService`·`GeminiService` 3곳이 주입받는다 |
| 응답 타임아웃 | 요청마다 `.timeout(...)`. 추론 30초(`fastapi.timeout-seconds`), Gemini 15초(`gemini.timeout-seconds`) — Gemini는 없어도 되는 부가 코멘트라 더 짧게 끊는다 |
| 실패 응답 | `AiServiceUnavailableException` 신설 → **503**. `HttpConnectTimeoutException`/`ConnectException`(연결 실패)과 `HttpTimeoutException`(응답 지연)을 분리해 메시지를 다르게 준다 |
| 트랜잭션 분리 | `AnalysisTransactionService` / `KioskTransactionService` **별도 빈**을 새로 만들었다. `analyze()`에서 `@Transactional`을 뗐다 |
| 상태 복구 | `Visit.rollbackAnalysis(previous)` — 인자 없는 기존 버전을 교체 |
| HikariCP | `maximum-pool-size=10`, `connection-timeout=5000`(기본 30초 → 5초) |
| 빈 `imageIds` | 서비스 진입부에서 `IllegalArgumentException` → 400 |

> 📌 **왜 같은 클래스의 private 메서드로 나누지 않았나.** Spring의 `@Transactional`은 프록시로 동작하는데,
> `this.saveResult(...)`처럼 자기 자신을 부르면 프록시를 타지 않아 **애노테이션이 조용히 무시된다.**
> 트랜잭션 경계를 진짜로 나누려면 호출이 빈 경계를 넘어야 하므로 별도 클래스가 필요하다.
> 컴파일도 테스트도 통과하는데 트랜잭션만 안 걸리는, 눈에 안 보이는 종류의 실수다.

> 📌 **`rollbackAnalysis`에 직전 상태를 넘기는 이유.** 기존 코드에도 복구 호출이 있었지만
> **같은 트랜잭션 안이라 실제로는 죽은 코드**였다(예외가 나면 `markAnalyzing()`까지 통째로 롤백됐다).
> 트랜잭션을 쪼갠 지금은 `ANALYZING`이 DB에 실제로 커밋되므로 복구가 반드시 동작해야 한다.
> 이때 무조건 `IN_PROGRESS`로 내리면 **재분석 실패 시 직전 분석 결과가 화면에서 사라진다.**
> 그래서 분석 시작 시점의 상태를 기억해 그 값으로 되돌린다.

> 📌 **빈 `imageIds`는 HTTP 경로에서는 이미 막혀 있었다.** `AnalysisRequest.imageIds`의 `@NotEmpty` +
> 컨트롤러 `@Valid`가 400을 낸다. 서비스 가드는 그 뒤를 받치는 이중 방어다 —
> 컨트롤러를 거치지 않는 호출(테스트, 내부 호출)에서 `get(0)`이 500으로 터지는 걸 막는다.

**검증 결과** (로컬 도커 실서버 + `./gradlew test`)

| 확인 항목 | 결과 |
|---|---|
| 정상 분석 흐름 (회귀) | 200 / 1.4초, `top1=nv`, 상태 `ANALYZED` |
| FastAPI 컨테이너 정지 후 분석 | **503 / 5.3초** — `"AI 분석 서버에 연결할 수 없습니다."` (수정 전에는 무한 대기) |
| 실패 후 접수 상태 | `ANALYZING`에 갇히지 않고 직전 상태(`ANALYZED`)로 복구 |
| **AI 장애 중 다른 API** | `GET /api/v1/visits?date=` → **200 / 0.01초**. 커넥션 풀이 마르지 않는다 |
| 빈 `imageIds` | 400 `{"imageIds": "must not be empty"}` |
| `./gradlew test` | 9/9 통과 (`AnalysisResilienceTest` 2건 신규) |

> `AnalysisResilienceTest`는 "죽은 서버"가 아니라 **"살아 있는데 응답하지 않는 서버"**를 재현한다.
> 요청을 받고 60초간 침묵하는 가짜 HTTP 서버를 띄우고 타임아웃을 2초로 낮춰 같은 코드 경로를 태운다.
> 죽으면 연결이 즉시 거절돼 저절로 끝나지만, 멈추기만 하면 타임아웃이 없는 한 영원히 묶인다 —
> 진료실에서 훨씬 흔하고 훨씬 나쁜 쪽은 후자다.

---

## 5. 종합

설계 판단(Visit FSM 8상태, `ImageStorageService` 추상화, Top-K 후보 제시, 키오스크 토큰 base62 설계)은
**의료 도메인 이해도가 높은 수준**이다. 지적된 문제 대부분은 "몰라서"가 아니라
**2인 체제로 백엔드+AI를 동시에 감당하며 기능 구현 속도를 우선한 결과**로 보인다.

즉 아키텍처를 갈아엎을 필요는 없고, 이 계획의 항목들은 대부분 **기존 설계 위에
인가·감사·트랜잭션 경계를 "끼워 넣는" 작업**이다. 그래서 G0가 반나절, G1이 하루로 끝난다.

다만 (F)만은 성격이 다르다. 그건 끼워 넣는 작업이 아니라 **배포 전에 반드시 닫아야 하는 문**이다.

> **G0·G1을 실제로 해 보고 확인된 것**: 예상 공수(반나절 + 1일)는 대체로 맞았다.
> 다만 두 이슈 모두 **본문에 없던 문제를 각각 1건씩 추가로 발견**했다
> (#1의 README 키 노출, #2의 hook 누수). 계획서의 지적은 대개 정확하지만 **완전하지는 않으므로**,
> G2 이후에도 "이슈에 적힌 것만 고치고 닫는" 방식은 피할 것.
>
> 그리고 이번에 드러난 (J) — **백엔드 테스트가 컴파일조차 되지 않는다** — 는 이 계획의
> 우선순위를 한 군데 바꾼다. G4로 미뤄 둔 **CI(감사 32번)를 G2와 함께 앞당기는 것이 맞다.**
> 테스트가 죽어 있는 것을 6개월간 아무도 몰랐다는 사실 자체가, 남은 G2~G4 작업이
> 조용히 깨질 수 있다는 뜻이기 때문이다. `compileTestJava`만 도는 워크플로 파일 하나면 시작된다.
