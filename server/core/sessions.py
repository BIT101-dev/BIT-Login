import logging
import threading
import time


logger = logging.getLogger("bit_login_server")


class SessionManager:
    def __init__(self):
        self._cache = {}
        self._ttl = 1800
        self._lock = threading.Lock()
        self._key_locks = {}
        self._key_locks_lock = threading.Lock()

    def get_key_lock(self, username, service_name):
        key = (username, service_name)
        with self._key_locks_lock:
            if key not in self._key_locks:
                self._key_locks[key] = threading.RLock()
            return self._key_locks[key]

    def get_session(self, username, service_name):
        key = (username, service_name)
        with self._lock:
            if key in self._cache:
                session, timestamp = self._cache[key]
                if time.time() - timestamp < self._ttl:
                    return session
                del self._cache[key]
                session.close()
        return None

    def set_session(self, username, service_name, session):
        key = (username, service_name)
        with self._lock:
            old_session = self._cache.get(key, (None, None))[0]
            self._cache[key] = (session, time.time())
        if old_session is not None and old_session is not session:
            old_session.close()

    def invalidate(self, username, service_name):
        key = (username, service_name)
        with self._lock:
            value = self._cache.pop(key, None)
        if value is not None:
            value[0].close()

    def cleanup_expired_sessions(self):
        while True:
            time.sleep(300)
            current_time = time.time()
            expired_sessions = []
            with self._lock:
                for key, value in list(self._cache.items()):
                    if current_time - value[1] > self._ttl:
                        expired_sessions.append(self._cache.pop(key)[0])
            for session in expired_sessions:
                session.close()
            if expired_sessions:
                logger.info("Cleaned up %s expired sessions.", len(expired_sessions))


session_manager = SessionManager()
