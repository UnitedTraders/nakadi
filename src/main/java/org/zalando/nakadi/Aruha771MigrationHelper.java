package org.zalando.nakadi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.zalando.nakadi.config.JsonConfig;
import org.zalando.nakadi.domain.Subscription;
import org.zalando.nakadi.util.HashGenerator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Script to be used for DB migration of ARUHA-771
 */
public class Aruha771MigrationHelper {

    private final HashGenerator hashGenerator;
    private final ObjectMapper jsonMapper;
    private final String url;
    private final String user;
    private final String pass;

    public static void main(final String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Pass args: <nakadi DB url> <username> <password>");
            System.exit(1);
        }
        final Aruha771MigrationHelper helper = new Aruha771MigrationHelper(
                new HashGenerator(),
                new JsonConfig().jacksonObjectMapper(), args[0], args[1], args[2]);
        helper.fillSubscriptionsHashes();
    }

    public Aruha771MigrationHelper(final HashGenerator hashGenerator, final ObjectMapper jsonMapper,
                                   final String dbUrl, final String dbUser, final String dbPass) throws Exception {
        this.hashGenerator = hashGenerator;
        this.jsonMapper = jsonMapper;
        user = dbUser;
        url = dbUrl;
        pass = dbPass;
        Class.forName("org.postgresql.Driver").newInstance();
    }

    public void fillSubscriptionsHashes() throws Exception {

        try (Connection con = DriverManager.getConnection(url, user, pass)) {
            final Statement st = con.createStatement();
            final ResultSet rs = st.executeQuery("SELECT s_subscription_object FROM zn_data.subscription");

            int i = 0;
            while (rs.next()) {
                final String jsonSubscription = rs.getString("s_subscription_object");
                final Subscription subscription = jsonMapper.readValue(jsonSubscription, Subscription.class);
                final String hash = hashGenerator.generateSubscriptionKeyFieldsHash(subscription);

                final Statement statement = con.createStatement();
                statement.execute("UPDATE zn_data.subscription SET s_key_fields_hash='" + hash + "' " +
                        "WHERE s_id='" + subscription.getId() + "'");

                System.out.println(++i + " | " + subscription.getId() + " | " + hash);
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

}
