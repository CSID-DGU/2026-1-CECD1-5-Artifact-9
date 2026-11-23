#!/bin/bash
# =============================================
# AI 인스턴스 시작 스크립트 — EC2 시작 템플릿의 "사용자 데이터"에 이 파일 내용을 그대로 붙여 넣는다.
# =============================================
# 대상: Amazon Linux 2023 (arm64) · t4g 계열(Graviton). 오토스케일링 그룹이 인스턴스를 새로 띄울 때마다 한 번 돈다.
#
#   1) 스왑 2GB — t4g.small(메모리 2GB)에서 모델 두 개를 올릴 때의 여유
#   2) 도커 설치·기동
#   3) ECR 로그인 → AI 이미지(artifact-ai:latest) 받기. 인스턴스 역할(IAM)로 로그인하므로 키가 없다
#   4) INTERNAL_API_SECRET 을 SSM 파라미터 스토어(SecureString)에서 읽기 — 시작 템플릿에 비밀값을 적지 않는다
#   5) 컨테이너를 8000 번으로 띄우기. 내부 ALB 대상 그룹이 /health/ready 로 상태를 본다
#
# 인스턴스 역할에 필요한 권한: deploy/aws/iam/ai-instance-policy.json (+ AmazonSSMManagedInstanceCore)
# 진행 로그: 인스턴스 안의 /var/log/artifact-ai-user-data.log (Session Manager 로 접속해 본다)
# 절차 전체: docs/aws-elb-asg-guide.md
#
# ⚠️ set -x 를 켜지 않는다 — 비밀값이 로그에 찍힌다.
# =============================================
set -euo pipefail

# ---- 바꿀 값은 이 세 줄뿐이다 ----
ECR_REPOSITORY="artifact-ai"
IMAGE_TAG="latest"
SECRET_PARAMETER="/artifact/INTERNAL_API_SECRET"

exec >>/var/log/artifact-ai-user-data.log 2>&1
echo "=== $(date -Is) AI 인스턴스 준비 시작"

# IMDSv2 로 리전·계정을 읽는다(시작 템플릿에서 IMDSv2 필수로 둔다).
IMDS_TOKEN=$(curl -sS -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 300")
imds() { curl -sS -H "X-aws-ec2-metadata-token: ${IMDS_TOKEN}" "http://169.254.169.254/latest/$1"; }
REGION=$(imds meta-data/placement/region)
ACCOUNT_ID=$(imds dynamic/instance-identity/document | python3 -c 'import json, sys; print(json.load(sys.stdin)["accountId"])')
REGISTRY="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"
IMAGE="${REGISTRY}/${ECR_REPOSITORY}:${IMAGE_TAG}"
echo "리전 ${REGION} · 이미지 ${IMAGE}"

# 1) 스왑 — 이미 있으면 건너뛴다(재부팅 뒤 다시 돌아도 안전하게).
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile swap swap defaults 0 0' >> /etc/fstab
fi

# 2) 도커
dnf install -y docker
systemctl enable --now docker

# 3) ECR 로그인 + 이미지
aws ecr get-login-password --region "${REGION}" | docker login --username AWS --password-stdin "${REGISTRY}"
docker pull "${IMAGE}"

# 4) 비밀값 — 파일로 넘겨 프로세스 목록(ps)에 값이 보이지 않게 한다.
#    변수에 먼저 받는다: printf 인자 안의 $(...) 가 실패하면 set -e 가 잡지 못해 빈 값이 파일에 쓰인다.
ENV_FILE=/etc/artifact-ai.env
umask 077
SECRET=$(aws ssm get-parameter --region "${REGION}" --name "${SECRET_PARAMETER}" \
  --with-decryption --query Parameter.Value --output text)
if [ -z "${SECRET}" ] || [ "${SECRET}" = "None" ]; then
  echo "SSM 파라미터 ${SECRET_PARAMETER} 가 비어 있습니다" >&2
  exit 1
fi
printf 'INTERNAL_API_SECRET=%s\n' "${SECRET}" > "${ENV_FILE}"
unset SECRET

# 5) 컨테이너. 로그는 10MB × 3개에서 돌려 써서 디스크가 차지 않게 한다.
docker rm -f artifact-ai >/dev/null 2>&1 || true
docker run -d --name artifact-ai --restart unless-stopped \
  -p 8000:8000 --env-file "${ENV_FILE}" \
  --log-opt max-size=10m --log-opt max-file=3 \
  "${IMAGE}"

echo "=== $(date -Is) 컨테이너 기동 요청 완료 — 모델 로드까지 30~90초 걸린다(대상 그룹의 /health/ready 가 200 이 되면 투입)"
