import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Card } from "../components/Card";
import { Input } from "../components/Input";

export default function Login() {
  const navigate = useNavigate();
  const [id, setId] = useState("");
  const [password, setPassword] = useState("");

  const handleLogin = (e: React.FormEvent) => {
    e.preventDefault();

    if (id.trim() && password.trim()) {
      // 로그인 성공 시 /main 으로 리다이렉트
      navigate("/main"); 
    } else {
      alert("아이디와 비밀번호를 입력해 주세요.");
    }
  };

  return (
    <div className="min-h-screen bg-gray-900 flex items-center justify-center p-4">
      <div className="w-full max-w-[360px]">
        <Card title="로그인">
          <form onSubmit={handleLogin} className="flex flex-col gap-4 mt-2">
            <Input 
              label="아이디" 
              placeholder="Username" 
              value={id}
              onChange={(v) => setId(v)} 
            />
            <Input 
              label="비밀번호" 
              placeholder="Password" 
              type="password"
              value={password}
              onChange={(v) => setPassword(v)} 
            />
            <button
              type="submit"
              className="w-full h-[40px] bg-blue-600 hover:bg-blue-500 text-white font-bold text-xs rounded mt-2 cursor-pointer transition-colors"
            >
              로그인
            </button>
          </form>
        </Card>
      </div>
    </div>
  );
}