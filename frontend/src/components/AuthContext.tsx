import { useState } from "react";
import type { ReactNode } from "react";

import { AuthContext, type User } from "../contexts/auth";

// 하드코딩: 로그인 회원 정보
const MOCK_USER_TABLE: Record<string, { pw: string; name: string }> = {
  "doctor1": { pw: "doc123", name: "김과장" },
  "nurse1": { pw: "nur123", name: "이팀장" },
  "admin": { pw: "1234", name: "박주임" },
};

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(() => {
    const savedUser = localStorage.getItem("his_user");
    if (!savedUser) return null;

    try {
      return JSON.parse(savedUser) as User;
    } catch {
      localStorage.removeItem("his_user");
      return null;
    }
  });

  const login = async (id: string, pw: string): Promise<boolean> => {
    await new Promise((resolve) => setTimeout(resolve, 300));

    const foundUser = MOCK_USER_TABLE[id];

    if (foundUser && foundUser.pw === pw) {
      const loggedInUser: User = {
        id: id,
        name: foundUser.name,
      };

      setUser(loggedInUser);
      localStorage.setItem("his_user", JSON.stringify(loggedInUser));
      return true;
    }

    return false;
  };

  const logout = () => {
    setUser(null);
    localStorage.removeItem("his_user");
  };

  return (
    <AuthContext.Provider value={{ user, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}
