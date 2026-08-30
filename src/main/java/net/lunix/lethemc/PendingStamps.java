package net.lunix.lethemc;

/**
 * A block entity whose life stamps were read before it was ready to hold them.
 *
 * <p>Exists because {@code VaultBlockEntity.serverData} is still null while
 * {@code loadAdditional} runs, so the stamps have to be parked and moved across once the object
 * is fully built. The flush is driven from {@code BlockEntity.setLevel}, which is inherited
 * rather than declared on the vault -- and Mixin cannot target an inherited method on a
 * subclass, so the hook lives on the base class and calls back through this interface.
 */
public interface PendingStamps {

    /** Move any parked stamps into place. Safe to call repeatedly and when there is nothing to do. */
    void lethemc$flushStamps();
}
