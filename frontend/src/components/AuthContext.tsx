import { createContext, useContext, useState, useEffect } from "react";
import type { ReactNode } from "react";
import { apiRequest } from "../api/client";

export interface Doctor {
  doctorId: number;
  loginId: string;
  name: string;
}

interface AuthContextType {
  user: Doctor | null;
  login: (loginId: string, password: string) => Promise<boolean>;
  signup: (payload: SignupPayload) => Promise<boolean>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

const STORAGE_KEY = "his_doctor";

export type SignupPayload = {
  loginId: string;
  password: string;
  name: string;
  licenseNumber?: string | null;
  department?: string | null;
};

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Doctor | null>(null);

  useEffect(() => {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved) setUser(JSON.parse(saved));
  }, []);

  const login = async (loginId: string, password: string): Promise<boolean> => {
    try {
      const doctor = await apiRequest<Doctor>("/api/v1/auth/login", {
        method: "POST",
        body: JSON.stringify({ loginId, password }),
      });
      setUser(doctor);
      localStorage.setItem(STORAGE_KEY, JSON.stringify(doctor));
      return true;
    } catch {
      return false;
    }
  };

  const signup = async (payload: SignupPayload): Promise<boolean> => {
    try {
      const doctor = await apiRequest<Doctor>("/api/v1/auth/signup", {
        method: "POST",
        body: JSON.stringify(payload),
      });
      setUser(doctor);
      localStorage.setItem(STORAGE_KEY, JSON.stringify(doctor));
      return true;
    } catch {
      return false;
    }
  };

  const logout = () => {
    setUser(null);
    localStorage.removeItem(STORAGE_KEY);
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
