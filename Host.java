import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * PASO 1 - HOST
 * Se ejecuta en el ordenador que va a ser controlado.
 * - Responde a las búsquedas de los clientes en la red local (UDP).
 * - Espera conexiones, pide permiso al usuario y envía la pantalla (TCP).
 */
public class Host {

    private static final int PUERTO = 5000;
    private static final int PUERTO_DESCUBRIMIENTO = 5001;
    private static final String MENSAJE_BUSQUEDA = "BUSCAR_HOST";
    private static final String PREFIJO_RESPUESTA = "HOST";
    private static final String SEPARADOR = "|";
    private static final int FPS = 10;

    /** Calidad JPEG: 0.0 (peor) a 1.0 (mejor). 0.9 se lee bien sin pesar demasiado. */
    private static final float CALIDAD_JPEG = 0.9f;

    /** Identificador único de este Host, para que el cliente no lo muestre repetido. */
    private static final String ID_HOST = UUID.randomUUID().toString();

    public static void main(String[] args) throws Exception {
        iniciarDescubrimiento();

        Robot robot = new Robot();
        Rectangle pantalla = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());

        try (ServerSocket servidor = new ServerSocket(PUERTO)) {
            System.out.println("Host '" + nombreEquipo() + "' (" + ipPrincipal()
                    + ") esperando conexiones en el puerto " + PUERTO + "...");

            while (true) {
                Socket socket = servidor.accept();
                String ip = socket.getInetAddress().getHostAddress();
                System.out.println("Solicitud de conexión desde " + ip);

                if (pedirPermiso(ip)) {
                    System.out.println("Conexión aceptada: " + ip);
                    JFrame ventanaSesion = mostrarVentanaSesion(socket, ip);
                    enviarPantalla(socket, robot, pantalla);
                    SwingUtilities.invokeLater(ventanaSesion::dispose);
                    System.out.println("Sesión terminada con " + ip);
                } else {
                    System.out.println("Conexión rechazada: " + ip);
                    socket.close();
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Descubrimiento en red local
    // ------------------------------------------------------------------

    /**
     * Hilo que escucha por UDP. Cuando un cliente pregunta "BUSCAR_HOST", responde:
     *   HOST|<id único>|<IP principal>|<nombre del equipo>
     * El cliente usa el id para agrupar las respuestas del mismo PC
     * y la IP principal para quedarse solo con la buena.
     */
    private static void iniciarDescubrimiento() {
        Thread hilo = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(PUERTO_DESCUBRIMIENTO)) {
                byte[] buffer = new byte[256];

                while (true) {
                    DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
                    socket.receive(paquete);

                    String mensaje = new String(paquete.getData(), 0, paquete.getLength(), StandardCharsets.UTF_8);
                    if (MENSAJE_BUSQUEDA.equals(mensaje)) {
                        String texto = PREFIJO_RESPUESTA + SEPARADOR + ID_HOST + SEPARADOR
                                + ipPrincipal() + SEPARADOR + nombreEquipo();
                        byte[] respuesta = texto.getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(respuesta, respuesta.length,
                                paquete.getAddress(), paquete.getPort()));
                    }
                }
            } catch (IOException e) {
                System.out.println("No se pudo iniciar la búsqueda en red: " + e.getMessage());
            }
        });
        hilo.setDaemon(true);
        hilo.start();
    }

    /**
     * IP real del equipo en la red (la que usa para salir al router).
     * Truco: "conectar" un socket UDP a una IP externa no envía nada,
     * pero hace que el sistema elija la tarjeta de red buena.
     * Así se descartan las IPs de VirtualBox, VMware, WSL, etc.
     */
    private static String ipPrincipal() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            String ip = socket.getLocalAddress().getHostAddress();
            if (!ip.startsWith("0.")) return ip;
        } catch (IOException ignorada) {
            // Sin conexión a Internet: lo dejamos vacío y el cliente elige
        }
        return "";
    }

    private static String nombreEquipo() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "Equipo sin nombre";
        }
    }

    // ------------------------------------------------------------------
    // Permiso y ventana de sesión
    // ------------------------------------------------------------------

    /** Muestra un diálogo siempre visible para que el usuario acepte o rechace. */
    private static boolean pedirPermiso(String ip) {
        JOptionPane opcion = new JOptionPane(
                "El equipo " + ip + " quiere ver tu pantalla.\n¿Permitir la conexión?",
                JOptionPane.WARNING_MESSAGE,
                JOptionPane.YES_NO_OPTION);

        JDialog dialogo = opcion.createDialog("Solicitud de conexión remota");
        dialogo.setAlwaysOnTop(true);
        dialogo.setVisible(true);
        dialogo.dispose();

        Object valor = opcion.getValue();
        return valor instanceof Integer && (Integer) valor == JOptionPane.YES_OPTION;
    }

    /**
     * Ventana siempre visible mientras dura la sesión.
     * Si el usuario la cierra o pulsa "Cortar conexión", se cierra el socket
     * y el envío de pantalla se detiene inmediatamente.
     */
    private static JFrame mostrarVentanaSesion(Socket socket, String ip) {
        JFrame ventana = new JFrame("Sesión remota activa");

        Runnable cortar = () -> {
            try {
                socket.close();
            } catch (IOException ignorada) {
                // Ya estaba cerrado
            }
            ventana.dispose();
        };

        SwingUtilities.invokeLater(() -> {
            JLabel texto = new JLabel("El equipo " + ip + " está viendo tu pantalla");
            texto.setForeground(Color.WHITE);
            texto.setFont(texto.getFont().deriveFont(Font.BOLD, 13f));

            JButton botonCortar = new JButton("Cortar conexión");
            botonCortar.addActionListener(e -> cortar.run());

            JPanel contenido = new JPanel(new BorderLayout(10, 0));
            contenido.setBackground(new Color(200, 40, 40));
            contenido.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            contenido.add(texto, BorderLayout.CENTER);
            contenido.add(botonCortar, BorderLayout.EAST);

            ventana.setContentPane(contenido);
            ventana.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            ventana.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    cortar.run();
                }
            });
            ventana.setAlwaysOnTop(true);
            ventana.setResizable(false);
            ventana.pack();

            // Esquina superior derecha de la pantalla
            Dimension tamanoPantalla = Toolkit.getDefaultToolkit().getScreenSize();
            ventana.setLocation(tamanoPantalla.width - ventana.getWidth() - 20, 20);
            ventana.setVisible(true);
        });

        return ventana;
    }

    // ------------------------------------------------------------------
    // Envío de pantalla
    // ------------------------------------------------------------------

    /**
     * Captura la pantalla a su resolución REAL.
     * Si Windows tiene el escalado al 125% o 150%, createScreenCapture() normal
     * devuelve la imagen reducida y el texto se ve borroso. Con la versión
     * multirresolución nos quedamos con la variante de más píxeles.
     */
    private static BufferedImage capturarPantalla(Robot robot, Rectangle pantalla) {
        MultiResolutionImage multi = robot.createMultiResolutionScreenCapture(pantalla);

        Image mejor = null;
        for (Image variante : multi.getResolutionVariants()) {
            if (mejor == null || variante.getWidth(null) > mejor.getWidth(null)) {
                mejor = variante;
            }
        }

        if (mejor instanceof BufferedImage) {
            return (BufferedImage) mejor;
        }
        BufferedImage copia = new BufferedImage(mejor.getWidth(null), mejor.getHeight(null),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copia.createGraphics();
        g.drawImage(mejor, 0, 0, null);
        g.dispose();
        return copia;
    }

    /** Comprime la imagen en JPEG con la calidad indicada. */
    private static byte[] comprimirJpeg(BufferedImage imagen, ImageWriter escritor, ImageWriteParam parametros)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ImageOutputStream salidaImagen = ImageIO.createImageOutputStream(buffer)) {
            escritor.setOutput(salidaImagen);
            escritor.write(null, new IIOImage(imagen, null, null), parametros);
        }
        return buffer.toByteArray();
    }

    /** Envía capturas en bucle: [tamaño en bytes][imagen JPEG] */
    private static void enviarPantalla(Socket socket, Robot robot, Rectangle pantalla) {
        ImageWriter escritor = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam parametros = escritor.getDefaultWriteParam();
        parametros.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        parametros.setCompressionQuality(CALIDAD_JPEG);

        try (DataOutputStream salida = new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream()))) {

            // Primero enviamos el tamaño de la pantalla (lo usaremos en el paso 2 para el ratón)
            salida.writeInt(pantalla.width);
            salida.writeInt(pantalla.height);
            salida.flush();

            while (!socket.isClosed()) {
                BufferedImage captura = capturarPantalla(robot, pantalla);
                byte[] bytes = comprimirJpeg(captura, escritor, parametros);

                salida.writeInt(bytes.length);
                salida.write(bytes);
                salida.flush();

                Thread.sleep(1000 / FPS);
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("Conexión terminada: " + e.getMessage());
        } finally {
            escritor.dispose();
        }
    }
}