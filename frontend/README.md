# Artifact Frontend

피부 질환 AI 보조 진단 시스템의 웹 클라이언트. React 19 + TypeScript + Vite + Tailwind CSS v4.

접수 · 진료 · 조회 · 증명서 발급 4개 업무 화면과, 로그인 없이 도는 2개 공개 화면
(태블릿 키오스크, QR 문서 열람)을 한 SPA 안에 담았다.

---

## 실행

```bash
npm install
npm run dev        # http://localhost:5173
```

dev 서버는 `/api` 요청을 `http://localhost:8080`(백엔드)으로 프록시한다
(`vite.config.ts`의 `server.proxy`). 백엔드를 따로 띄워야 화면이 동작한다.

전체 스택을 한 번에 띄우려면 저장소 루트에서:

```bash
docker compose up -d --build   # http://localhost:3000
```

| 명령 | 하는 일 |
|---|---|
| `npm run dev` | Vite dev 서버 (HMR) |
| `npm run build` | `tsc -b` 타입체크 후 `dist/` 생성 |
| `npm run lint` | ESLint |
| `npm run preview` | 빌드 결과를 로컬에서 서빙 |

---

## API 주소를 어떻게 찾는가

**모든 API 호출은 상대경로 `/api/v1/...` 로 하드코딩되어 있다** (`src/api/*.ts`).
호스트를 붙이지 않는 이유는 하나다 — 프론트와 API가 항상 같은 오리진에서 나오게
만들어 CORS·쿠키 SameSite 문제를 아예 만들지 않기 위해서다.

같은 오리진을 만들어주는 주체만 환경마다 다르다:

| 환경 | `/api` 를 백엔드로 넘기는 주체 |
|---|---|
| 로컬 dev | Vite `server.proxy` (`vite.config.ts`) |
| Docker / EC2 | nginx (`frontend/nginx.conf`의 `location /api/`) |
| AWS (이전 검토 중) | CloudFront 동작 `/api/*` → ALB |

> `.env.*` 에 `VITE_API_BASE_URL=/api` 가 남아 있지만 **코드에서 읽지 않는다**
> (`src/vite-env.d.ts` 에도 선언이 없다). 값을 바꿔도 아무 효과가 없으니,
> API 주소를 옮기려면 위 표의 프록시 설정을 고쳐야 한다.

### 실제로 쓰는 환경 변수

| 변수 | 용도 |
|---|---|
| `VITE_KIOSK_BASE_URL` | 접수 화면이 만드는 키오스크 QR 의 기본 주소. 비우면 현재 origin 사용 |

키오스크 주소는 재빌드 없이 접수 화면의 "키오스크 QR" 카드에서 덮어쓸 수 있고,
그 값이 `localStorage` 에 남아 빌드 시점 값보다 우선한다 (`src/utils/kioskUrl.ts`).
부스에서 Wi-Fi 대역이 바뀔 때 빌드를 다시 하지 않으려고 넣은 경로다.

---

## 화면 구성

```
/                     로그인
/main                 접수      (RECEPTION)
/main/clinic          진료      (DOCTOR)
/main/lookup          조회
/main/certificate     증명서 발급
/kiosk                태블릿 대기 화면      ← 인증 없음
/kiosk/:token         태블릿 예비분석 화면  ← 인증 없음
/d/c/:token           QR 증명서 열람        ← 인증 없음 + 생년월일 확인
/d/v/:token           QR 진료요약 열람      ← 인증 없음 + 생년월일 확인
```

업무 화면은 `PrivateRoute`(로그인 여부) → `RoleRoute`(역할별 화면 접근) 두 겹을
통과해야 들어간다. 역할 ↔ 화면 매핑은 `src/auth/roles.ts` 한 곳에만 있다.

공개 화면 4개는 라우터 바깥이 아니라 **`PrivateRoute` 밖에** 놓여 있다. 이 구분이
무너지면 로그인 없이 환자 데이터가 열리므로, 새 라우트를 추가할 때 어느 쪽에
넣는지 반드시 확인한다.

---

## 폴더 구조

```
src/
├── api/            서버 호출 — 도메인별 파일 1개
│   ├── client.ts       fetch 래퍼. 토큰 부착, 타임아웃, ApiError 변환
│   ├── errors.ts       상태 코드 → 사용자 문구 변환표
│   ├── session.ts      토큰 보관 / 만료 알림
│   └── ...             analysis, certificates, documents, images,
│                       kiosk, patients, prescription, print, reference, visits
├── auth/
│   ├── AuthContext.ts  컨텍스트 타입·훅
│   └── roles.ts        역할 ↔ 접근 가능 화면 매핑
├── components/     공용 UI + 라우트 가드 (PrivateRoute, RoleRoute, MainLayout …)
├── pages/          화면 9개
├── types/          공용 타입
└── utils/          confidence(신뢰도 구간), datetime, kioskUrl
```

### 손대기 전에 알아두면 좋은 것

- **화면에 띄울 오류 문구를 직접 만들지 않는다.** `getErrorMessage()`(`api/errors.ts`)에
  맡긴다. 같은 상태 코드가 화면마다 다른 말을 하면 사용자가 원인을 짚지 못한다.
- **`ApiError.status` 는 HTTP 코드지만 두 개는 우리가 만든 값이다.**
  `0`(= `NETWORK_ERROR_STATUS`, 서버에 닿지 못함)과 `408`(= `REQUEST_TIMEOUT_STATUS`,
  우리가 대기 한도를 넘겨 직접 끊음)은 부스에서 대응이 완전히 다르다 —
  전자는 네트워크를 보고, 후자는 잠시 기다린다.
- **인증이 필요한 이미지는 `<img src>` 로 바로 못 건다.** `AuthedImage` 를 쓴다.
- **신뢰도 구간 판정은 `utils/confidence.ts` 한 곳에서만 한다.** 임계값을 화면마다
  복사하면 백엔드와 어긋나는 순간 찾을 수 없다.

---

## 배포

`Dockerfile` 이 멀티스테이지로 빌드한 뒤 nginx 로 서빙한다(`nginx.conf` 포함).
`index.html` 과 JS/CSS 는 `no-store` 로 내려 배포 직후 구버전이 남지 않게 한다.

- EC2 배포 절차: [`../docs/ec2-deployment-guide.md`](../docs/ec2-deployment-guide.md)
- CI/CD: [`../docs/cicd-guide.md`](../docs/cicd-guide.md)
- S3 + CloudFront 정적 호스팅 전환 검토: [`../docs/aws-architecture-migration-guide.md`](../docs/aws-architecture-migration-guide.md)
