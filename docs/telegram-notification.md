# 팀 시연용 텔레그램 알림

접수 저장 후 notification/TelegramNotificationService가 고정된 팀 그룹으로
기존 QR 목적지 `/kiosk/{token}` 버튼을 비동기 전송한다.

## 설정

1. 공식 @BotFather에서 /newbot으로 봇 생성.
2. 팀 비공개 그룹에 봇을 추가하고 메시지 발송 권한 확인.
3. 그룹에서 /start@봇사용자명 전송 후 Bot API getUpdates의 message.chat.id 확인.
   음수 부호까지 보존한다. 기존 webhook과 getUpdates는 동시에 사용할 수 없다.
4. 루트 .env에 아래 값을 설정한다. 토큰은 Git이나 채팅에 올리지 않는다.

```dotenv
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=발급받은값
TELEGRAM_CHAT_ID=팀그룹ID
TELEGRAM_PUBLIC_BASE_URL=https://서비스주소
```

서비스 주소는 경로 없는 HTTPS origin이다. 접수 화면의 키오스크 주소와 일치해야 한다.
주소가 다르면 발송을 생략하며 헤더가 없으면 서버 설정을 사용한다.
Docker Compose backend 컨테이너를 재생성해야 환경변수가 적용된다.
IntelliJ 직접 실행은 Run Configuration에 환경변수를 설정한다.

## 확인

가상 환자 접수 → 그룹 메시지 도착 → 버튼 URL과 QR URL 일치 확인 →
휴대폰 버튼 클릭 → 업로드·분석까지 확인한다.
비활성화는 TELEGRAM_ENABLED=false로 설정하고 backend를 재생성한다.

기본값은 비활성화. 활성화 시 모든 신규 접수가 팀 그룹에 전달되므로 가상 환자 시연용으로만 쓴다.
이름·전화번호·메모·이미지는 보내지 않는다. 링크 자체에는 접수 접근 토큰이 있으므로
그룹 구성원을 팀원으로 제한한다. 링크 미리보기는 끄고 콘텐츠 보호 옵션을 적용한다.
토큰 만료는 기존 키오스크 정책을 따른다.

10초 요청 타임아웃, 동시 요청 10건 제한. 발송 실패로 접수가 취소되지는 않는다.
자동 재시도·영구 큐는 없으므로 서버 종료나 전송 실패 시 알림이 유실될 수 있다.
API 성공은 발송 API 수락이며 수신자의 실제 열람을 뜻하지 않는다.

공식 문서: https://core.telegram.org/bots/api#sendmessage
