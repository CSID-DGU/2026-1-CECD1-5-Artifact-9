import { createContext } from "react";

export interface User {
  id: string;
  name: string;
}

export interface AuthContextType {
  user: User | null;
  login: (id: string, pw: string) => Promise<boolean>;
  logout: () => void;
}

export const AuthContext = createContext<AuthContextType | undefined>(undefined);
