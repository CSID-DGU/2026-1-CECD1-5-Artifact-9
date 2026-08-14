import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Card } from "../components/Card";
import { Input } from "../components/Input";
import { useAuth } from "../auth/AuthContext";
import { ArtifactLogo } from "../components/ArtifactLogo";

const ROLE_OPTIONS = [
  { value: "DOCTOR", label: "의사" },
  { value: "NURSE",  label: "간호사" },
  { value: "STAFF",  label: "일반 (접수)" },
];

export default function Login() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<"login" | "signup">("login");
  const [id, setId] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [role, setRole] = useState("DOCTOR");
  const [department, setDepartment] = useState("피부과");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const { login, signup, sessionExpired } = useAuth();

  const switchMode = (next: "login" | "signup") => {
    setMode(next);
    setErrorMessage(null);
  };

  /**
   * 로그인/회원가입 공통 제출 처리.
   *
   * <p>실패 문구를 alert가 아니라 폼 안에 띄운다. alert는 확인을 눌러야 사라지고,
   * 사라지고 나면 무엇이 틀렸는지 다시 볼 수 없다. 그리고 서버가 준 사유
   * ("이미 사용 중인 아이디입니다" 등)를 그대로 쓴다 — 전에는 이걸 버리고
   * "아이디 중복 또는 입력값을 확인하세요"라고 추측해서 보여줬다.
   */
  const submit = async (e: React.FormEvent, run: () => Promise<{ ok: boolean; message?: string }>) => {
    e.preventDefault();
    if (submitting) return;

    setSubmitting(true);
    setErrorMessage(null);
    try {
      const result = await run();
      if (result.ok) {
        navigate("/main");
      } else {
        setErrorMessage(result.message ?? "요청 처리 중 오류가 발생했습니다.");
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleLogin = (e: React.FormEvent) => submit(e, () => login(id, password));

  const handleSignup = (e: React.FormEvent) =>
    submit(e, () =>
      signup({
        loginId: id,
        password,
        name,
        role,
        department: department.trim() || null,
      })
    );

  return (
    <div className="min-h-screen bg-gray-900 flex items-center justify-center p-4">
      <div className="w-full max-w-[360px]">
        <div className="mb-7">
          <ArtifactLogo />
        </div>
        <Card title={mode === "login" ? "로그인" : "회원가입"}>
          {/* 토큰 만료로 튕겨 나온 경우. 사용자 입장에선 갑자기 로그인 화면이 뜬 것이라 이유를 알려준다. */}
          {sessionExpired && !errorMessage && (
            <div className="mb-4 rounded border border-amber-700 bg-amber-950/60 px-3 py-2 text-xs text-amber-200">
              로그인 유효시간이 지나 자동으로 로그아웃되었습니다. 다시 로그인해 주세요.
            </div>
          )}
          <div className="mb-4 grid grid-cols-2 gap-1 rounded bg-gray-800 p-1 text-xs">
            <button
              type="button"
              onClick={() => switchMode("login")}
              className={`h-8 rounded transition-colors ${mode === "login" ? "bg-blue-600 text-white" : "text-gray-300 hover:bg-gray-700"}`}
            >
              로그인
            </button>
            <button
              type="button"
              onClick={() => switchMode("signup")}
              className={`h-8 rounded transition-colors ${mode === "signup" ? "bg-blue-600 text-white" : "text-gray-300 hover:bg-gray-700"}`}
            >
              회원가입
            </button>
          </div>
          <form onSubmit={mode === "login" ? handleLogin : handleSignup} className="flex flex-col gap-4 mt-2">
            {mode === "signup" && (
              <>
                <Input
                  label="이름"
                  placeholder="홍길동"
                  value={name}
                  onChange={(v) => setName(v)}
                  autoComplete="name"
                  required
                  maxLength={50}
                />
                <div className="flex flex-col gap-1">
                  <label className="text-xs text-gray-400">직책</label>
                  <select
                    value={role}
                    onChange={(e) => setRole(e.target.value)}
                    className="h-9 rounded bg-gray-800 border border-gray-700 text-white text-xs px-2 focus:outline-none focus:border-blue-500"
                  >
                    {ROLE_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>{opt.label}</option>
                    ))}
                  </select>
                </div>
                <Input
                  label="진료과"
                  placeholder="피부과"
                  value={department}
                  onChange={(v) => setDepartment(v)}
                  autoComplete="organization-title"
                  maxLength={100}
                />
              </>
            )}
            {/* 플레이스홀더에 실제 테스트 계정(admin/1234)이 적혀 있었다. 공개 저장소의 화면이라
                누구나 볼 수 있었고, 서버는 아직 최소 길이 검증이 없어 그 계정이 그대로 살아 있었다.
                안내 문구는 서버가 실제로 강제하는 규칙만 적는다 — @Size(max) 외에 제약이 없으므로
                "8자 이상" 같은 문구는 쓰지 않는다. */}
            <Input
              label="아이디"
              placeholder={mode === "login" ? "아이디를 입력하세요" : "로그인에 사용할 아이디"}
              value={id}
              onChange={(v) => setId(v)}
              autoComplete="username"
              required
              maxLength={50}
            />
            <Input
              label="비밀번호"
              placeholder={mode === "login" ? "비밀번호를 입력하세요" : "로그인에 사용할 비밀번호"}
              type="password"
              value={password}
              onChange={(v) => setPassword(v)}
              autoComplete={mode === "login" ? "current-password" : "new-password"}
              required
              maxLength={100}
            />
            {errorMessage && (
              <div
                role="alert"
                className="rounded border border-red-800 bg-red-950/60 px-3 py-2 text-xs text-red-200"
              >
                {errorMessage}
              </div>
            )}
            <button
              type="submit"
              disabled={submitting}
              className="w-full h-[40px] bg-blue-600 hover:bg-blue-500 disabled:bg-gray-700 disabled:cursor-not-allowed text-white font-bold text-xs rounded mt-2 cursor-pointer transition-colors"
            >
              {submitting
                ? "처리 중…"
                : mode === "login"
                  ? "로그인"
                  : "가입하고 시작"}
            </button>
          </form>
        </Card>
      </div>
    </div>
  );
}
