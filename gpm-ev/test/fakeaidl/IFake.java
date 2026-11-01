package fakeaidl;

/** Stand-in for an AIDL interface on the car's boot classpath (MultiCarTest). */
public interface IFake {
    float getCarSpeed(byte unit);
    String getVIN();
    int getSoh();
    void registerListener(IFakeListener l, android.os.IBinder cb);
    int overloaded(int a);
    int overloaded(long a);
    float noStubField(int a);

    abstract class Stub implements IFake {
        static final int TRANSACTION_getCarSpeed = 1 + 10;
        static final int TRANSACTION_getVIN = 1 + 46;
        static final int TRANSACTION_getSoh = 1 + 213;
        static final int TRANSACTION_registerListener = 1;
        static final int TRANSACTION_overloaded = 5;
    }
}
