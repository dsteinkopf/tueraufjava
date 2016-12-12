package net.steinkopf.tuerauf.service;

import net.steinkopf.tuerauf.SecurityContextTest;
import net.steinkopf.tuerauf.TestConstants;
import net.steinkopf.tuerauf.TueraufApplication;
import net.steinkopf.tuerauf.data.User;
import net.steinkopf.tuerauf.repository.UserRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

/**
 * Integration tests for {@link UserService}.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = TueraufApplication.class)
public class UserServiceTest extends SecurityContextTest {

    private final static Logger logger = LoggerFactory.getLogger(UserServiceTest.class);


    @Autowired
    UserRepository userRepository;

    /**
     * System under Test.
     */
    @Autowired
    UserService userService;


    @Before
    public void setup() throws Exception {

        super.setup();
    }

    @After
    public void tearDown() throws Exception {

        // delete users created by any test.
        // those from import.sql must not be deleted.
        Stream.of(0L, 4L, 5L, 6L)
                .map(id -> userRepository.findOne(id))
                .filter(user -> user != null)
                .forEach(user -> userRepository.delete(user));
    }

    @Test
    public void testActivateAllNew() throws Exception {

        // Prepare
        int inActiveBefore = userRepository.findByActive(false).size();
        int activeBefore = userRepository.findByActive(true).size();

        User user1 = new User("testInstallationId");
        user1.setUsername("UserServiceTest user");
        int serialId = userService.findFreeSerialId();
        user1.setSerialId(serialId);
        userRepository.save(user1);

        assertThat(userRepository.findByActive(false).size(), is(equalTo(inActiveBefore + 1)));
        assertThat(userRepository.findByActive(true).size(), is(equalTo(activeBefore)));

        // Run
        userService.activateAllNew();

        // Check
        assertThat(userRepository.findByActive(false).size(), is(equalTo(0)));
        assertThat(userRepository.findByActive(true).size(), is(equalTo(activeBefore + inActiveBefore + 1)));
    }

    @Test
    public void testFindFreeSerialId() throws Exception {

        List<User> userList = new ArrayList<>();

        final int USER_TO_DELETE = 1;
        int serialIdToBeReused = createThenDeleteSomeUsers("serId ", userList, USER_TO_DELETE);

        // re-create
        User user = new User("testInstId99");
        user.setUsername("user 99");
        int serialId = userService.findFreeSerialId();
        user.setSerialId(serialId);
        userRepository.save(user);

        // check
        assertThat(serialId, is(equalTo(serialIdToBeReused)));
    }

    /**
     * create, then delete some users
     *
     * @return serialId to be reused
     */
    private int createThenDeleteSomeUsers(final String prefix, final List<User> userList, final int userToDelete) {

        // first create some Users, then delete one. This serialId should be reused then.
        for (int userNumber = 0; userNumber < 4; userNumber++) {
            User user = new User(prefix + "InstId" + userNumber);
            user.setUsername(prefix + "user " + userNumber);
            int serialId = userService.findFreeSerialId();
            user.setSerialId(serialId);
            userRepository.save(user);
            logger.debug("created {}", user);

            userList.add(userNumber, user);
        }

        // remember serialId and delete
        int serialIdToBeReused = userList.get(userToDelete).getSerialId();
        userRepository.delete(userList.get(userToDelete));
        return serialIdToBeReused;
    }

    @Test(expected = IndexOutOfBoundsException.class)
    @Transactional // makes insertions be rolled back on exception.
    public void testFindFreeSerialIdOverflow() throws Exception {

        userRepository.findAll().forEach(user1 -> logger.debug("all users: {}", user1.toString()));

        // create more users than available serialIds
        for (int userNumber = 0; userNumber < UserService.MAX_SERIAL_ID + 1; userNumber++) {
            User user = new User("ovfl InstId" + userNumber);
            user.setUsername("ovfl user " + userNumber);
            int serialId = userService.findFreeSerialId();
            user.setSerialId(serialId);
            logger.debug("save user with serialId {}", serialId);
            userRepository.save(user);
        }
    }

    @Test
    public void testGetPinList() throws Exception {

        // Prepare
        List<User> userList = new ArrayList<>();
        final int USER_TO_DELETE = 2;
        createThenDeleteSomeUsers("pin ", userList, USER_TO_DELETE);

        // Run
        final String[] pinList = userService.getActivePinList();

        // Check
        for (int serialId = 0; serialId < UserService.MAX_SERIAL_ID; serialId++) {
            final String pin = pinList[serialId];
            switch (serialId) {
                case TestConstants.SERIAL_ID_ALMOST_ACTIVE:
                    assertThat(pin, is(equalTo(TestConstants.PIN_ALMOST_ACTIVE)));
                    break;
                case TestConstants.SERIAL_ID_INACTIVE:
                    assertThat(pin, is(equalTo(null))); // inactive user's pin not sent.
                    break;
                default:
                    assertThat(pin, is(equalTo(null)));
                    break;
            }
        }
    }

    @Test
    public void testRegisterOrUpdateUser() throws Exception {

        User user1 = userService.registerOrUpdateUser("User1", "1111", "InstIdUser1");
        User user1b = userService.registerOrUpdateUser("User1b", "1112", "InstIdUser1");
        assertThat(user1b.getId(), is(equalTo(user1.getId())));

        User user1read = userRepository.findByInstallationId("InstIdUser1").get(0);
        assertThat(user1read.getId(), is(equalTo(user1.getId())));
        assertThat(user1read.getUsername(), is(equalTo("User1b")));
        assertThat(user1read.getUsernameOld(), is(equalTo("User1")));
    }

    @Test(expected = UserService.DuplicateUsernameException.class)
    public void testRegisterOrUpdateUserExists() throws Exception {

        User user1 = userService.registerOrUpdateUser("User1", "1111", "InstIdUser1");
        User user2 = userService.registerOrUpdateUser("User1", "1112", "InstIdUser2");
    }
}
