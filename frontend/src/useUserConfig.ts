import { useEffect, useState } from 'react';
import { getUserConfig } from './api';
import { useAuth, getAccessToken } from './auth';

interface UserConfig {
  isAdmin: boolean;
  grantedToolGroups: string[];
}

export function useUserConfig(): UserConfig {
  const { user } = useAuth();
  const [isAdmin, setIsAdmin] = useState(false);
  const [grantedToolGroups, setGrantedToolGroups] = useState<string[]>([]);

  useEffect(() => {
    const fetchUserConfig = async () => {
      try {
        const token = await getAccessToken();
        const config = await getUserConfig(token);
        setIsAdmin(config.isAdmin);
        setGrantedToolGroups(config.grantedToolGroups);
      } catch {
        setIsAdmin(false);
        setGrantedToolGroups(['web']);
      }
    };
    fetchUserConfig();
  }, [user]);

  return { isAdmin, grantedToolGroups };
}
