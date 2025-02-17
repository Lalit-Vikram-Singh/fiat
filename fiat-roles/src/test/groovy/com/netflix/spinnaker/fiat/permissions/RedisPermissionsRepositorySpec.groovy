/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.fiat.permissions

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.fiat.config.UnrestrictedResourceConfig
import com.netflix.spinnaker.fiat.model.Authorization
import com.netflix.spinnaker.fiat.model.UserPermission
import com.netflix.spinnaker.fiat.model.resources.Account
import com.netflix.spinnaker.fiat.model.resources.Application
import com.netflix.spinnaker.fiat.model.resources.BuildService
import com.netflix.spinnaker.fiat.model.resources.Permissions
import com.netflix.spinnaker.fiat.model.resources.Role
import com.netflix.spinnaker.fiat.model.resources.ServiceAccount
import com.netflix.spinnaker.kork.jedis.JedisClientDelegate
import com.netflix.spinnaker.kork.jedis.RedisClientDelegate
import io.github.resilience4j.retry.RetryRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import net.jpountz.lz4.LZ4CompressorWithLength
import net.jpountz.lz4.LZ4DecompressorWithLength
import net.jpountz.lz4.LZ4Factory
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.util.SafeEncoder
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Subject

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock

class RedisPermissionsRepositorySpec extends Specification {

  private static final String EMPTY_PERM_JSON = "{}"

  private static final String UNRESTRICTED = UnrestrictedResourceConfig.UNRESTRICTED_USERNAME

  @Shared
  @AutoCleanup("stop")
  GenericContainer embeddedRedis

  @Shared
  ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)

  RedisPermissionRepositoryConfigProps configProps = new RedisPermissionRepositoryConfigProps(prefix: "unittests")

  @Shared
  Jedis jedis

  @Shared
  PausableRedisClientDelegate redisClientDelegate

  @Shared
  LZ4CompressorWithLength lz4compressor

  @Shared
  LZ4DecompressorWithLength lz4decompressor

  @Subject
  RedisPermissionsRepository repo

  Clock clock = new TestClock()

  def setupSpec() {
    embeddedRedis = new GenericContainer(DockerImageName.parse("redis:5-alpine")).withExposedPorts(6379)
    embeddedRedis.start()
    def jedisPool = new JedisPool(embeddedRedis.host, embeddedRedis.getMappedPort(6379))
    jedis = jedisPool.getResource()
    jedis.flushDB()
    redisClientDelegate = new PausableRedisClientDelegate(new JedisClientDelegate(jedisPool))
    LZ4Factory factory = LZ4Factory.fastestInstance()
    lz4compressor = new LZ4CompressorWithLength(factory.fastCompressor())
    lz4decompressor = new LZ4DecompressorWithLength(factory.fastDecompressor())
  }

  private static class TestClock extends Clock {
    private Instant instant = Instant.now()
    @Override
    ZoneId getZone() {
      return ZoneId
    }

    @Override
    Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException()
    }

    @Override
    Instant instant() {
      return instant
    }

    void tick(Duration amount) {
      this.instant = instant.plus(amount)
    }
  }

  static class PausableRedisClientDelegate implements RedisClientDelegate {
    private final RedisClientDelegate delegate
    private final AtomicReference<CountDownLatch> pauseLatch = new AtomicReference<>()
    private final ReadWriteLock latchLock = new ReentrantReadWriteLock()
    private final AtomicReference<CountDownLatch> pausedNotification = new AtomicReference<>()

    PausableRedisClientDelegate(RedisClientDelegate delegate) {
      this.delegate = delegate
    }

    void pause(CountDownLatch pausedNotification) {
      latchLock.writeLock().lock()
      try {
        pauseLatch.set(new CountDownLatch(1))
        this.pausedNotification.set(pausedNotification)
      } finally {
        latchLock.writeLock().unlock()
      }
    }

    void resume() {
      latchLock.writeLock().lock()
      try {
        if (pauseLatch.get() != null) {
          pauseLatch.get().countDown()
          pauseLatch.set(null)
          pausedNotification.set(null)
        }
      } finally {
        latchLock.writeLock().unlock()
      }
    }

    @Delegate
    private RedisClientDelegate getDelegate() {
      latchLock.readLock().lock()
      CountDownLatch latch = null
      try {
        latch = pauseLatch.get()
        if (latch != null) {
          pausedNotification.get()?.countDown()
        }
      } finally {
        latchLock.readLock().unlock()
      }
      if (latch != null) {
        latch.await()
      }
      return delegate
    }
  }

  def setup() {
    repo = new RedisPermissionsRepository(
        clock,
        objectMapper,
        redisClientDelegate,
        [new Application(), new Account(), new ServiceAccount(), new Role(), new BuildService()],
        configProps,
        RetryRegistry.ofDefaults()
    )
  }

  def cleanup() {
    jedis.flushDB()
  }

  def setCompressed(String key, String value) {
    byte[] k = SafeEncoder.encode(key)
    byte[] v = lz4compressor.compress(SafeEncoder.encode(value))
    jedis.set(k, v)
  }

  String getCompressed(String key) {
    byte[] k = SafeEncoder.encode(key)
    def val = jedis.get(k)

    if (val == null) {
      return null
    }

    return SafeEncoder.encode(lz4decompressor.decompress(val))
  }

  def "should fail if timeout is exceeded"() {
    given:
    repo.put(new UserPermission().setId("foo"))
    def exec = Executors.newFixedThreadPool(1)
    def latch = new CountDownLatch(1)

    when:
    redisClientDelegate.pause(latch)
    def result = exec.submit {
      repo.get("foo")
    }

    latch.await()

    //TODO(cfieber): apologies to all readers of this test code
    // this is gross, but a brief sleep will ensure the repo.get
    // call attempts to make a redis call and hits the pause
    // point in the PausableRedisClientDelegate.
    Thread.sleep(5)
    clock.tick(configProps.repository.getPermissionTimeout.plusMillis(1))
    redisClientDelegate.resume()
    result.get()

    then:
    def ex = thrown(ExecutionException)
    ex.cause instanceof PermissionReadException
    ex.cause.message.contains("timeout")
  }

  def "should set last modified for unrestricted user on save"() {
    expect:
    !jedis.sismember("unittests:users", UNRESTRICTED)
    !jedis.exists("unittests:last_modified:__unrestricted_user__")

    when:
    repo.put(new UserPermission().setId(UNRESTRICTED))

    then:
    jedis.sismember("unittests:users", UNRESTRICTED)
    jedis.exists("unittests:last_modified:__unrestricted_user__")
  }

  def "should put the specified permission in redis"() {
    setup:
    Account account1 = new Account().setName("account")
    Application app1 = new Application().setName("app")
    ServiceAccount serviceAccount1 = new ServiceAccount().setName("serviceAccount")
                                                         .setMemberOf(["role1"])
    Role role1 = new Role("role1")

    when:
    repo.put(new UserPermission()
                 .setId("testUser")
                 .setAccounts([account1] as Set)
                 .setApplications([app1] as Set)
                 .setServiceAccounts([serviceAccount1] as Set)
                 .setRoles([role1] as Set))

    then:
    jedis.smembers("unittests:users") == ["testuser"] as Set
    jedis.smembers("unittests:roles:role1") == ["testuser"] as Set

    getCompressed("unittests:permissions-v2:testuser:accounts") ==
        '{"account":{"name":"account","permissions":{}}}'
    getCompressed("unittests:permissions-v2:testuser:applications") ==
        '{"app":{"name":"app","permissions":{},"details":{}}}'
    getCompressed("unittests:permissions-v2:testuser:service_accounts") ==
        '{"serviceAccount":{"name":"serviceAccount","memberOf":["role1"]}}'
    getCompressed("unittests:permissions-v2:testuser:roles") ==
        '{"role1":{"name":"role1"}}'
    !jedis.sismember ("unittests:permissions:admin","testUser")
  }

  def "should remove permission that has been revoked"() {
    setup:
    jedis.sadd("unittests:users", "testuser")
    jedis.sadd("unittests:roles:role1", "testuser")
    setCompressed("unittests:permissions-v2:testuser:accounts", '{"account":{"name":"account","permissions":{}}}')
    setCompressed("unittests:permissions-v2:testuser:applications", '{"app":{"name":"app","permissions":{}}}')
    setCompressed("unittests:permissions-v2:testuser:service_accounts", '{"serviceAccount":{"name":"serviceAccount"}}')
    setCompressed("unittests:permissions-v2:testuser:roles", '{"role1":{"name":"role1"}}')

    when:
    repo.put(new UserPermission()
                 .setId("testUser")
                 .setAccounts([] as Set)
                 .setApplications([] as Set)
                 .setServiceAccounts([] as Set)
                 .setRoles([] as Set))

    then:
    getCompressed("unittests:permissions-v2:testuser:accounts") == null
    getCompressed("unittests:permissions-v2:testuser:applications") == null
    getCompressed("unittests:permissions-v2:testuser:service_accounts") == null
    getCompressed("unittests:permissions-v2:testuser:roles") == null
    jedis.smembers("unittests:roles:role1") == [] as Set
  }

  def "should get the permission out of redis"() {
    setup:
    jedis.sadd("unittests:users", "testuser")
    setCompressed("unittests:permissions-v2:testuser:accounts",
               '{"account":{"name":"account","permissions":{"READ":["abc"]}}}')
    setCompressed("unittests:permissions-v2:testuser:applications",
               '{"app":{"name":"app","permissions":{"READ":["abc"]}}}')
    setCompressed("unittests:permissions-v2:testuser:service_accounts",
               '{"serviceAccount":{"name":"serviceAccount"}}')

    when:
    def result = repo.get("testuser").get()

    then:
    def abcRead = new Permissions.Builder().add(Authorization.READ, "abc").build()
    def expected = new UserPermission()
        .setId("testUser")
        .setAccounts([new Account().setName("account").setPermissions(abcRead)] as Set)
        .setApplications([new Application().setName("app").setPermissions(abcRead)] as Set)
        .setServiceAccounts([new ServiceAccount().setName("serviceAccount")] as Set)
    result == expected

    when:
    setCompressed("unittests:permissions-v2:__unrestricted_user__:accounts",
               '{"account":{"name":"unrestrictedAccount","permissions":{}}}')
    jedis.set("unittests:last_modified:__unrestricted_user__", "1")
    result = repo.get("testuser").get()

    then:
    expected.addResource(new Account().setName("unrestrictedAccount"))
    result == expected
  }

    def "should put all users to redis"() {
      setup:
      def account1 = new Account().setName("account1")
      def account2 = new Account().setName("account2")

      def testUser1 = new UserPermission().setId("testUser1")
              .setAccounts([account1] as Set)
      def testUser2 = new UserPermission().setId("testUser2")
              .setAccounts([account2] as Set)
      def testUser3 = new UserPermission().setId("testUser3")
              .setAdmin(true)

      when:
      repo.putAllById([
        "testuser1": testUser1,
        "testuser2": testUser2,
        "testuser3": testUser3,
      ])

      then:
      jedis.smembers("unittests:users") == ["testuser1", "testuser2", "testuser3"] as Set
      jedis.sismember("unittests:permissions:admin", "testuser3")
      getCompressed("unittests:permissions-v2:testuser1:accounts") ==
              '{"account1":{"name":"account1","permissions":{}}}'
      getCompressed("unittests:permissions-v2:testuser2:accounts") ==
              '{"account2":{"name":"account2","permissions":{}}}'
      getCompressed("unittests:permissions-v2:testuser3:applications") == null

    }

    def "should get all users from redis"() {
    setup:
    jedis.sadd("unittests:users", "testuser1", "testuser2", "testuser3")

    and:
    Account account1 = new Account().setName("account1")
    Application app1 = new Application().setName("app1")
    ServiceAccount serviceAccount1 = new ServiceAccount().setName("serviceAccount1")

    setCompressed("unittests:permissions-v2:testuser1:accounts",
               '{"account1":{"name":"account1","permissions":{}}}')
    setCompressed("unittests:permissions-v2:testuser1:applications",
               '{"app1":{"name":"app1","permissions":{}}}')
    setCompressed("unittests:permissions-v2:testuser1:service_accounts",
               '{"serviceAccount1":{"name":"serviceAccount1"}}')

    and:
    def abcRead = new Permissions.Builder().add(Authorization.READ, "abc").build()
    Account account2 = new Account().setName("account2").setPermissions(abcRead)
    Application app2 = new Application().setName("app2").setPermissions(abcRead)
    ServiceAccount serviceAccount2 = new ServiceAccount().setName("serviceAccount2")
    setCompressed("unittests:permissions-v2:testuser2:accounts",
               '{"account2":{"name":"account2","permissions":{"READ":["abc"]}}}')
    setCompressed("unittests:permissions-v2:testuser2:applications",
               '{"app2":{"name":"app2","permissions":{"READ":["abc"]}}}')
    setCompressed("unittests:permissions-v2:testuser2:service_accounts",
               '{"serviceAccount2":{"name":"serviceAccount2"}}')
    and:
    jedis.sadd("unittests:permissions:admin", "testuser3")

    when:
    def result = repo.getAllById()

    then:
    def testUser1 = new UserPermission().setId("testUser1")
                                        .setAccounts([account1] as Set)
                                        .setApplications([app1] as Set)
                                        .setServiceAccounts([serviceAccount1] as Set)
    def testUser2 = new UserPermission().setId("testUser2")
                                        .setAccounts([account2] as Set)
                                        .setApplications([app2] as Set)
                                        .setServiceAccounts([serviceAccount2] as Set)
    def testUser3 = new UserPermission().setId("testUser3")
                                        .setAdmin(true)
    result == ["testuser1": testUser1.getRoles(), "testuser2": testUser2.getRoles(), "testuser3": testUser3.getRoles()]
  }

  def "should delete the specified user"() {
    given:
    jedis.keys("*").size() == 0

    Account account1 = new Account().setName("account")
    Application app1 = new Application().setName("app")
    Role role1 = new Role("role1")

    when:
    repo.put(new UserPermission()
                 .setId("testUser")
                 .setAccounts([account1] as Set)
                 .setApplications([app1] as Set)
                 .setRoles([role1] as Set)
                 .setAdmin(true))

    then:
    jedis.keys("*").size() == 6 // users, accounts, applications, roles, and reverse-index roles.
    jedis.sismember("unittests:permissions:admin", "testuser")

    when:
    repo.remove("testuser")

    then:
    jedis.keys("*").size() == 0
  }

  def "should get all by roles"() {
    setup:
    def role1 = new Role("role1")
    def role2 = new Role("role2")
    def role3 = new Role("role3")
    def role4 = new Role("role4")
    def role5 = new Role("role5")

    def acct1 = new Account().setName("acct1")

    def user1 = new UserPermission().setId("user1").setRoles([role1, role2] as Set)
    def user2 = new UserPermission().setId("user2").setRoles([role1, role3] as Set)
    def user3 = new UserPermission().setId("user3") // no roles.
    def user4 = new UserPermission().setId("user4").setRoles([role4] as Set)
    def user5 = new UserPermission().setId("user5").setRoles([role5] as Set)
    def unrestricted = new UserPermission().setId(UNRESTRICTED).setAccounts([acct1] as Set)

    setCompressed("unittests:permissions-v2:user1:roles", '{"role1":{"name":"role1"},"role2":{"name":"role2"}}')
    setCompressed("unittests:permissions-v2:user2:roles", '{"role1":{"name":"role1"},"role3":{"name":"role3"}}')
    setCompressed("unittests:permissions-v2:user4:roles", '{"role4":{"name":"role4"}}')
    setCompressed("unittests:permissions-v2:user5:roles", '{"role5":{"name":"role5"}}')

    setCompressed("unittests:permissions-v2:__unrestricted_user__:accounts", '{"acct1":{"name":"acct1"}}')

    jedis.sadd("unittests:roles:role1", "user1", "user2")
    jedis.sadd("unittests:roles:role2", "user1")
    jedis.sadd("unittests:roles:role3", "user2")
    jedis.sadd("unittests:roles:role4", "user4")
    jedis.sadd("unittests:roles:role5", "user5", "USER5")

    jedis.sadd("unittests:users", "user1", "user2", "user3", "user4", "user5", "USER5", "__unrestricted_user__")

    when:
    def result = repo.getAllByRoles(["role1"])

    then:
    result == ["user1"       : user1.getRoles() + unrestricted.getRoles(),
               "user2"       : user2.getRoles() + unrestricted.getRoles(),
               (UNRESTRICTED): unrestricted.getRoles()]

    when:
    result = repo.getAllByRoles(["role3", "role4"])

    then:
    result == ["user2"       : user2.getRoles() + unrestricted.getRoles(),
               "user4"       : user4.getRoles() + unrestricted.getRoles(),
               (UNRESTRICTED): unrestricted.getRoles()]

    when:
    result = repo.getAllByRoles(null)

    then:
    result == ["user1"       : user1.getRoles() + unrestricted.getRoles(),
               "user2"       : user2.getRoles() + unrestricted.getRoles(),
               "user3"       : user3.getRoles() + unrestricted.getRoles(),
               "user4"       : user4.getRoles() + unrestricted.getRoles(),
               "user5"       : user5.getRoles() + unrestricted.getRoles(),
               (UNRESTRICTED): unrestricted.getRoles()]

    when:
    result = repo.getAllByRoles([])

    then:
    result == [(UNRESTRICTED): unrestricted.getRoles()]

    when:
    result = repo.getAllByRoles(["role5"])

    then:
    result == ["user5"       : user5.getRoles() + unrestricted.getRoles(),
               (UNRESTRICTED): unrestricted.getRoles()]
  }
}
