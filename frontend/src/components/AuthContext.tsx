import { createContext, useContext, useState } from "react";
import type { ReactNode } from "react";
import { apiRequest } from "../api/client";
import { STORAGE_KEYS } from "../constants";

export interface Member {
  memberId: number;
  loginId: string;
  name: string;
  role: string;
}

interface AuthContextType {
  user: Member | null;
  login: (loginId: string, password: string) => Promise<boolean>;
  signup: (payload: SignupPayload) => Promise<boolean>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function getToken(): string | null {
  return localStorage.getItem(STORAGE_KEYS.TOKEN);
}

export type SignupPayload = {
  loginId: string;
  password: string;
  name: string;
  role?: string;
  licenseNumber?: string | null;
  department?: string | null;
};

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Member | null>(() => {
    const saved = localStorage.getItem(STORAGE_KEYS.USER);
    return saved ? JSON.parse(saved) : null;
  });

  const login = async (loginId: string, password: string): Promise<boolean> => {
    try {
      const res = await apiRequest<Member & { token: string }>("/api/v1/auth/login", {
        method: "POST",
        body: JSON.stringify({ loginId, password }),
      });
      const { token, ...member } = res;
      setUser(member);
      localStorage.setItem(STORAGE_KEYS.USER, JSON.stringify(member));
      localStorage.setItem(STORAGE_KEYS.TOKEN, token);
      return true;
    } catch {
      return false;
    }
  };

  const signup = async (payload: SignupPayload): Promise<boolean> => {
    try {
      const res = await apiRequest<Member & { token: string }>("/api/v1/auth/signup", {
        method: "POST",
        body: JSON.stringify(payload),
      });
      const { token, ...member } = res;
      setUser(member);
      localStorage.setItem(STORAGE_KEYS.USER, JSON.stringify(member));
      localStorage.setItem(STORAGE_KEYS.TOKEN, token);
      return true;
    } catch {
      return false;
    }
  };

  const logout = () => {
    setUser(null);
    localStorage.removeItem(STORAGE_KEYS.USER);
    localStorage.removeItem(STORAGE_KEYS.TOKEN);
  };

  return (
    <AuthContext.Provider value={{ user, login, signup, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used within an AuthProvider");
  return context;
}
