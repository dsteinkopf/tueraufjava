package net.steinkopf.tuerauf.service;

import net.steinkopf.tuerauf.data.User;
import net.steinkopf.tuerauf.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.List;

/**
 * Manipulation of users.
 */
@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);


    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LogAndMailService logAndMailService;


    final static int MAX_SERIAL_ID = 16;


    /**
     * Activates all users which are not active and new.
     *
     * @return List of users that have been activated.
     */
    public List<User> activateAllNew() {

        logger.trace("activateAllNew");
        List<User> userList = userRepository.findByActiveFalseAndNewUserTrue();
        userList.forEach(user -> {
            user.setActive(true);
            userRepository.save(user);
        });
        return userList;
    }

    /**
     * Creates a user object or - if installationId exists - updates this user.
     *
     * @return created or updated user object.
     * @throws DuplicateUsernameException if this username already exists.
     */
    public User registerOrUpdateUser(final String username, final String pin, final String installationId)
            throws DuplicateUsernameException {

        Assert.isTrue(username.length() >= 2, "username must be at least 2 characters");
        Assert.isTrue(pin.length() == 4, "pin must be exactly 4 characters");
        Assert.isTrue(installationId.length() >= 10, "installationId must be at least 10 characters");

        List<User> existingUser = userRepository.findByInstallationId(installationId); // can only be 1 or none.
        User user;
        if (existingUser.isEmpty()) {
            existingUser = userRepository.findByUsername(username);  // can only be 1 or none.
            if (existingUser.isEmpty()) {
                // create new User
                user = new User(installationId);
                user.setSerialId(findFreeSerialId());
            }
            else {
                throw new DuplicateUsernameException("Username " + username + " already exists.");
            }
        } else {
            // update existing User
            user = existingUser.get(0);
            user.setNewUser(false);
        }
        user.updateData(username, pin);
        userRepository.save(user);

        // Log and send admin notifications:
        if (user.getUsernameOld() != null) {
            logAndMailService.logAndMail("user {} changed name to {} (serialId={})",
                    null,
                    user.getUsernameOld(),
                    user.getUsername(),
                    user.getSerialId()
            );
        }
        if (user.getPinOld() != null) {
            logAndMailService.logAndMail("user {} changed pin (serialId={})",
                    null,
                    user.getUsername(),
                    user.getSerialId()
            );
        }
        logAndMailService.logAndMail("user {} {} (serialId={})",
                null,
                user.getUsername(),
                existingUser.isEmpty() ? "created" : "updated",
                user.getSerialId()
        );

        return user;
    }

    /**
     * Checks if this user is active
     *
     * @return user object, if existing and active, null if not.
     */
    public User getUserIfActive(final String installationId) {

        final List<User> userList = userRepository.findByInstallationId(installationId);
        if (userList.size() == 0) {
            return null;
        }
        final User user = userList.get(0);
        if (!user.isActive()) {
            return null;
        }
        return user;
    }

    /**
     * Find the lowest free (usable) serial id
     *
     * @return free serial id.
     * @throws IndexOutOfBoundsException if all serial ids are used.
     */
    public int findFreeSerialId() throws IndexOutOfBoundsException {

        boolean serialIdIsUsed[] = new boolean[MAX_SERIAL_ID];

        // mark used serialIds
        userRepository.findAll().forEach(user -> serialIdIsUsed[user.getSerialId()] = true);

        // find first free serialId
        for (int serialId = 0; serialId < MAX_SERIAL_ID; serialId++) {
            if (!serialIdIsUsed[serialId]) {
                logger.debug("findFreeSerialId returns serialId {}", serialId);
                return serialId;
            }
        }

        // none found
        // TODO Global Exception Handler
        logAndMailService.logAndMail("too many users - MAX_SERIAL_ID reached", null);
        throw new IndexOutOfBoundsException("too many users - MAX_SERIAL_ID reached");
    }

    /**
     * Create an ArrayList of the pins of active users.
     * Note: After sending pins to Arduino, they are deleted in the local DB. So here we return only pins net yet deleted.
     *
     * @return Array of pins. Index of Array = serialId.
     */
    public String[] getActivePinList() {

        String[] pins = new String[MAX_SERIAL_ID];
        for (final User user : userRepository.findAll()) {
            if (user.getPin() != null && user.isActive()) {
                pins[user.getSerialId()] = user.getPin();
            }
        }
        return pins;
    }

    /**
     * Locally delete given pins.
     *
     * @param pinList Pins to be deleted.
     * @exception IllegalArgumentException if any user does not exist.
     */
    public void deletePins(final String[] pinList) throws IllegalArgumentException {

        for (int serialId = 0; serialId < pinList.length; serialId++) {
            if (pinList[serialId] != null) {
                final List<User> userList = userRepository.findBySerialId(serialId);
                if (userList.size() != 1) {
                    throw new IllegalArgumentException(String.format("User with serialId %s does not exist", serialId));
                }
                final User user = userList.get(0);
                user.setPin(null);
                userRepository.save(user);
            }
        }
    }

    /**
     * @return The number of currently existing users.
     */
    public long getUserCount() {
        return userRepository.count();
    }


    public class DuplicateUsernameException extends Exception {

        DuplicateUsernameException(final String message) {
            super(message);
        }
    }
}
