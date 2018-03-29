package se.kth.molguin.tracedemo.network;

class TokenManager {

    private final Object lock = new Object();
    private boolean hasToken;

    TokenManager() {
        hasToken = true;
    }

    void getToken() {
        synchronized (lock) {
            if (!hasToken) {
                try {
                    lock.wait();
                } catch (InterruptedException ignored) {
                }
            }
            
            hasToken = false;
        }
    }

}
