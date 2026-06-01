import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Card } from "../components/Card";
import { Input } from "../components/Input";
<<<<<<< Updated upstream
import { useAuth } from "../hooks/useAuth";
=======
import { useAuth } from "../components/AuthContext";
import { ArtifactLogo } from "../components/ArtifactLogo";
>>>>>>> Stashed changes

export default function Login() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<"login" | "signup">("login");
  const [id, setId] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [licenseNumber, setLicenseNumber] = useState("");
  const [department, setDepartment] = useState("");
  const { login, signup } = useAuth();

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();

    const success = await login(id, password);

    if (success) {
      // 로그인 성공 시 /main 으로 리다이렉트
      navigate("/main"); 
    } else {
      alert("아이디와 비밀번호가 올바르지 않습니다.");
    }
  };

  const handleSignup = async (e: React.FormEvent) => {
    e.preventDefault();

    const success = await signup({
      loginId: id,
      password,
      name,
      licenseNumber: licenseNumber.trim() || null,
      department: department.trim() || null,
    });

    if (success) {
      navigate("/main");
    } else {
      alert("회원가입에 실패했습니다. 아이디 중복 또는 입력값을 확인하세요.");
    }
  };

  return (
    <div className="min-h-screen bg-gray-900 flex items-center justify-center p-4">
      <div className="w-full max-w-[360px]">
        <div className="mb-7">
          <ArtifactLogo />
        </div>
        <Card title={mode === "login" ? "의사 로그인" : "의사 회원가입"}>
          <div className="mb-4 grid grid-cols-2 gap-1 rounded bg-gray-800 p-1 text-xs">
            <button
              type="button"
              onClick={() => setMode("login")}
              className={`h-8 rounded transition-colors ${mode === "login" ? "bg-blue-600 text-white" : "text-gray-300 hover:bg-gray-700"}`}
            >
              로그인
            </button>
            <button
              type="button"
              onClick={() => setMode("signup")}
              className={`h-8 rounded transition-colors ${mode === "signup" ? "bg-blue-600 text-white" : "text-gray-300 hover:bg-gray-700"}`}
            >
              회원가입
            </button>
          </div>
          <form onSubmit={mode === "login" ? handleLogin : handleSignup} className="flex flex-col gap-4 mt-2">
            {mode === "signup" && (
              <>
                <Input
                  label="의사 이름"
                  placeholder="홍길동"
                  value={name}
                  onChange={(v) => setName(v)}
                />
                <Input
                  label="면허번호"
                  placeholder="선택 입력"
                  value={licenseNumber}
                  onChange={(v) => setLicenseNumber(v)}
                />
                <Input
                  label="진료과"
                  placeholder="피부과"
                  value={department}
                  onChange={(v) => setDepartment(v)}
                />
              </>
            )}
            <Input 
              label="아이디" 
              placeholder="Testname: admin" 
              value={id}
              onChange={(v) => setId(v)} 
            />
            <Input 
              label="비밀번호" 
              placeholder="Testpwd: 1234" 
              type="password"
              value={password}
              onChange={(v) => setPassword(v)} 
            />
            <button
              type="submit"
              className="w-full h-[40px] bg-blue-600 hover:bg-blue-500 text-white font-bold text-xs rounded mt-2 cursor-pointer transition-colors"
            >
              {mode === "login" ? "로그인" : "가입하고 시작"}
            </button>
          </form>
        </Card>
      </div>
    </div>
  );
}
