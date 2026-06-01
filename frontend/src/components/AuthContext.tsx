import { createContext, useContext, useState, ReactNode, useEffect } from "react";

interface User {
  id: string;
  name: string;
}

interface AuthContextType {
  user: User | null;
  login: (id: string, pw: string) => Promise<boolean>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

// 하드코딩: 로그인 회원 정보
const MOCK_USER_TABLE: Record<string, { pw: string; name: string }> = {
  "doctor1": { pw: "doc123", name: "김과장" },
  "nurse1": { pw: "nur123", name: "이팀장" },
  "admin": { pw: "1234", name: "박주임" },
};

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);

  useEffect(() => {
    const savedUser = localStorage.getItem("his_user");
    if (savedUser) {
      setUser(JSON.parse(savedUser));
    }
  }, []);

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

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used within an AuthProvider");
  return context;
}