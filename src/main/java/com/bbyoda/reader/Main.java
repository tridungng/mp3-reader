package com.bbyoda.reader;

import com.mpatric.mp3agic.ID3v2;
import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.Mp3File;
import com.mpatric.mp3agic.UnsupportedTagException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;


public class Main {

    private static final String DB_URL = "jdbc:h2:~/mydatabase;AUTO_SERVER=TRUE";
    private static final String INIT_DB_URL = DB_URL + ";INIT=runscript from 'classpath:create.sql'";

    static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Please provide valid mp3 directory!");

        org.h2.tools.Server webServer = org.h2.tools.Server.createWebServer(
                "-webPort", "8082"
        ).start();

        System.out.println("H2 Web Console running at: " + webServer.getURL());

        String pathString = args[0];
        Path path = Paths.get(pathString);

        if (Files.notExists(path))
            throw new IllegalArgumentException("Specified directory does not exist: " + pathString);

        List<Path> mp3Paths = new ArrayList<>();

        try (DirectoryStream<Path> paths = Files.newDirectoryStream(path)) {
            paths.forEach(p -> System.out.println("File: " + p.getFileName()));
        }

        try (DirectoryStream<Path> paths = Files.newDirectoryStream(path, "*.mp3")) {
            paths.forEach(p -> {
                System.out.println("Found mp3 file: " + p.toString());
                mp3Paths.add(p);
            });
        }

        List<Audio> audios = mp3Paths.stream().map(p -> {
            try {
                Mp3File mp3File = new Mp3File(p);
                ID3v2 id3 = mp3File.getId3v2Tag();

                if (id3 == null) return new Audio("", "", "", "");

                return new Audio(id3.getArtist(), id3.getYear(), id3.getAlbum(), id3.getTitle());

            } catch (IOException | UnsupportedTagException | InvalidDataException e) {
                throw new RuntimeException(e);
            }
        }).toList();

        try (Connection conn = DriverManager.getConnection(INIT_DB_URL)) {
            System.out.println("Connected to the database successfully.");
            PreparedStatement st = conn.prepareStatement("INSERT INTO AUDIOS (artist, release_year, album, title) VALUES (?, ?, ?, ?);");

            for (Audio audio : audios) {
                st.setString(1, audio.artist());
                st.setString(2, audio.year());
                st.setString(3, audio.album());
                st.setString(4, audio.title());

                st.addBatch();
            }

            int[] updates = st.executeBatch();
            System.out.println("Inserted [=" + updates.length + "] records into the database.");

        }

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(8080);
        server.addConnector(connector);

        ServletContextHandler context =
                new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        context.addServlet(AudioServlet.class, "/songs");
        context.addServlet(HelloWorldServlet.class, "/hello");

        System.out.println("Server running at:" + connector.getHost() + ":" + connector.getPort());

        server.start();
        server.join();

    }

    public static class AudioServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            StringBuilder builder = new StringBuilder();

            try (Connection conn = DriverManager.getConnection("jdbc:h2:~/mydatabase")) {
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM AUDIOS");

                while (rs.next()) {
                    builder.append("<tr class=\"table\">")
                            .append("<td>").append(rs.getString("release_year")).append("</td>")
                            .append("<td>").append(rs.getString("artist")).append("</td>")
                            .append("<td>").append(rs.getString("album")).append("</td>")
                            .append("<td>").append(rs.getString("title")).append("</td>")
                            .append("</tr>");
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            String html = "<html><h1>Your audios</h1><table><tr><th>Year</th><th>Artist</th><th>Album</th><th>Title</th></tr>" + builder + "</table></html>";
            resp.getWriter().write(html);
        }
    }

    public static class HelloWorldServlet extends HttpServlet {
        
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().write("Hello, World!");
        }
    }

    public record Audio(String artist, String year, String album, String title) {
    }

}
