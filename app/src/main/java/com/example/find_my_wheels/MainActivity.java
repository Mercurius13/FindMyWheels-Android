package com.example.find_my_wheels;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.annotations.SerializedName;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = {Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE};
    private static final int REQUEST_ENABLE_BT = 1;
    private static final java.util.UUID UUID = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private PreviewView previewView;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private Executor executor = Executors.newSingleThreadExecutor();
    private ImageCapture imageCapture;
    private Spinner spinnerFilePaths;
    private Button btnCapture;
    private List<String> filePaths = new ArrayList<>();
    private BluetoothAdapter bluetoothAdapter;
    private Set<BluetoothDevice> pairedDevices;
    private BluetoothDevice hc05Device;
    private BluetoothSocket socket;
    private boolean isConnected = false;

    private static int slot_number = 1;
    private static final String path = "parking/";

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        spinnerFilePaths = findViewById(R.id.spinnerFilePaths);
        btnCapture = findViewById(R.id.btnCapture);
        btnCapture.setEnabled(false);

        spinnerFilePaths.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                btnCapture.setEnabled(true);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
                btnCapture.setEnabled(false);
            }
        });

        if (allPermissionsGranted()) {
            startCamera(); // Ensure camera is started
            initBluetooth();
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        fetchDataAndPopulateSpinner();

        btnCapture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Start capturing images immediately when the button is clicked
                captureImage();
                // Schedule the capturing of images every 10 seconds
                handler.postDelayed(captureRunnable, 10000); // 10 seconds in milliseconds
            }
        });
    }

    // Initialize Bluetooth
    private void initBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            // Device doesn't support Bluetooth
            Toast.makeText(this, "Bluetooth is not available on this device", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            // Bluetooth is not enabled, prompt the user to enable it
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            // Bluetooth is enabled, proceed to list paired devices
            listPairedDevices();
        }
    }

    // List paired devices
    private void listPairedDevices() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        pairedDevices = bluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : pairedDevices) {
            if (device.getName().equals("HC-05")) {
                hc05Device = device;
                break;
            }
        }
        if (hc05Device != null) {
            connectToDevice(hc05Device);
        } else {
            Toast.makeText(this, "HC-05 device not found", Toast.LENGTH_SHORT).show();
        }
    }

    private void connectToDevice(BluetoothDevice device) {
        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // Standard SerialPortService ID
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            socket = device.createRfcommSocketToServiceRecord(uuid);
            socket.connect();
            isConnected = true;
            Toast.makeText(this, "Connected to HC-05", Toast.LENGTH_SHORT).show();
            // Start listening for signals from HC-05
            startListening();
        } catch (IOException e) {
            Log.e("Bluetooth", "Error connecting to HC-05: " + e.getMessage(), e);
            Toast.makeText(this, "Failed to connect to HC-05", Toast.LENGTH_SHORT).show();
        }
    }

    private void startListening() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] buffer = new byte[1024];
                    int bytes;

                    while (true) {
                        bytes = socket.getInputStream().read(buffer);
                        String signal = new String(buffer, 0, bytes);
                        if (signal.equals("CaptureImage")) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    captureImage();
                                }
                            });
                        }
                    }
                } catch (IOException e) {
                    Log.e("Bluetooth", "Error listening for signals: " + e.getMessage(), e);
                }
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e("Bluetooth", "Error closing socket: " + e.getMessage(), e);
            }
        }
    }

    // Define a Handler and a Runnable
    private final Handler handler = new Handler();
    private final Runnable captureRunnable = new Runnable() {
        @Override
        public void run() {
            Toast.makeText(MainActivity.this, "Recieved Arduino Signal", Toast.LENGTH_SHORT).show();
            captureImage();
            Toast.makeText(MainActivity.this, "Image Taken And Sent To API", Toast.LENGTH_SHORT).show();
            handler.postDelayed(this, 7000);
        }
    };


    private void displayCategoryOptions(List<String> categories) {
        List<String> categoryList = new ArrayList<>(categories);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, categoryList);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerFilePaths.setAdapter(adapter);
    }

    private void fetchDataAndPopulateSpinner() {
        ApiService apiService = RetrofitClient.getClient().create(ApiService.class);
        Call<DataResponse> call = apiService.getData();
        call.enqueue(new Callback<DataResponse>() {
            @Override
            public void onResponse(Call<DataResponse> call, Response<DataResponse> response) {
                if (response.isSuccessful()) {
                    DataResponse dataResponse = response.body();
                    if (dataResponse != null) {
                        List<String> parkings = dataResponse.getParkings();
                        if (parkings != null) {
                            Log.d("DataResponse", "Parkings: " + parkings.toString());
                            displayCategoryOptions(parkings);
                        } else {
                            Log.e("API Response", "Parkings list is null");
                        }
                    } else {
                        Log.e("API Response", "Data response is null");
                    }
                } else {
                    Log.e("API Response", "Failed to fetch data from API: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<DataResponse> call, Throwable t) {
                Log.e("API Response", "Failed to fetch data from API: " + t.getMessage(), t);
            }
        });
    }

    public class ImageData {
        @SerializedName("image")
        private String image;

        @SerializedName("path")
        private String path;

        @SerializedName("slot")
        private int slot;

        public void setImage(String yourImageData) {
            this.image = yourImageData;
        }

        public void setPath(String yourPathData) {
            this.path = yourPathData;
        }

        public void setSlot(int i) {
            this.slot = i;
        }

        // Constructor, getters, and setters
    }


    private void uploadImage(String base64Image) {
        // Check if the image data is available
        if (base64Image == null || base64Image.isEmpty()) {
            Log.e("ImageUpload", "Base64 image data is empty");
            return;
        }

        // Create a Retrofit client instance
        ApiService apiService = RetrofitClient.getClient().create(ApiService.class);

        String selectedValue = path + (String) spinnerFilePaths.getSelectedItem();

        // Create a JSONObject to hold the data
        ImageData imageData = new ImageData();
        imageData.setImage(base64Image);
        imageData.setPath(selectedValue);
        imageData.setSlot(slot_number);
        slot_number += 1;

        // Make the POST request to upload the image
        Call<Void> call = apiService.uploadImage(imageData);
        call.enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    Log.d("ImageUpload", "Image uploaded successfully");
                    // Handle successful upload
                } else {
                    Log.e("ImageUpload", "Failed to upload image: " + response.code());
                    // Handle failed upload
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e("ImageUpload", "Failed to upload image: " + t.getMessage(), t);
                // Handle failure
            }
        });
    }


    private void startCamera() {
        Log.d("Camera", "Starting camera initialization...");
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                if (cameraProvider != null) {
                    Log.d("Camera", "Camera provider initialized successfully");
                    imageCapture = new ImageCapture.Builder()
                            .setTargetRotation(getWindowManager().getDefaultDisplay().getRotation())
                            .build();
                    bindCameraUseCases(cameraProvider);
                } else {
                    Log.e("Camera", "Camera provider is null");
                }
            } catch (ExecutionException | InterruptedException e) {
                Log.e("Camera", "Error initializing camera provider: " + e.getMessage(), e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        ImageCapture.Builder imageCaptureBuilder = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(getWindowManager().getDefaultDisplay().getRotation());

        imageCapture = imageCaptureBuilder.build();

        try {
            // Unbind use cases before rebinding
            cameraProvider.unbindAll();

            // Bind use cases to camera
            Camera camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

            // Optional: Add a listener to detect changes in camera state
            CameraControl cameraControl = camera.getCameraControl();
            cameraControl.enableTorch(false);

        } catch (Exception e) {
            Log.e("TAG", "Error binding camera use cases: " + e.getMessage(), e);
        }
    }


    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void captureImage() {
        Log.d("Camera", "Capturing image...");
        // Create a file to hold the image
        File photoFile = createTempFile();

        // Configure output options
        ImageCapture.OutputFileOptions outputFileOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        // Capture image and convert to Base64
        imageCapture.takePicture(outputFileOptions, executor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Log.d("Camera", "Image saved successfully");
                // Image saved successfully, now read the saved image file and convert to Base64
                try {
                    // Read the saved image file as a byte array
                    FileInputStream fileInputStream = new FileInputStream(photoFile);
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = fileInputStream.read(buffer)) != -1) {
                        byteArrayOutputStream.write(buffer, 0, length);
                    }
                    byte[] imageBytes = byteArrayOutputStream.toByteArray();

                    // Close the streams
                    fileInputStream.close();
                    byteArrayOutputStream.close();

                    // Convert the byte array to Base64
                    String base64Image = Base64.encodeToString(imageBytes, Base64.DEFAULT);

                    // Do something with the base64Image, such as sending it to a server
                    uploadImage(base64Image);
                } catch (IOException e) {
                    Log.e("Camera", "Failed to read saved image file: " + e.getMessage(), e);
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e("Camera", "Error capturing image: " + exception.getMessage(), exception);
            }
        });
    }


    private String getBatchDirectoryName() {
        String app_folder_path = "";
        app_folder_path = Environment.getExternalStorageDirectory().toString() + "/storage/images/";
        File dir = new File(app_folder_path);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e("ImageCapture", "Failed to create directory");
        }
        return app_folder_path;
    }

    private File createTempFile() {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        try {
            return File.createTempFile(imageFileName, ".jpg", storageDir);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public interface ApiService {
        String get_path = "/getdb?path=" + path;
        @GET(get_path)
        Call<DataResponse> getData();
        @POST("/image")
        Call<Void> uploadImage(@Body ImageData imageData);
    }

    public class RetrofitClient {
        private static Retrofit retrofit;
        private static final String BASE_URL = "http://65.20.84.196:8000/";
        private static final String API_KEY = "CONTACT-US-FOR-API-KEY";

        public static Retrofit getClient() {
            if (retrofit == null) {
                OkHttpClient.Builder httpClient = new OkHttpClient.Builder();

                // Add logging interceptor to log network traffic
                HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
                loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
                httpClient.addInterceptor(loggingInterceptor);

                httpClient.addInterceptor(new Interceptor() {
                    @Override
                    public okhttp3.Response intercept(Chain chain) throws IOException {
                        Request original = chain.request();
                        // Adding API key as a header
                        Request.Builder requestBuilder = original.newBuilder()
                                .addHeader("x-api-key", API_KEY)
                                .addHeader("accept", "application/json")
                                .addHeader("Content-Type", "application/json");

                        Request request = requestBuilder.build();
                        return chain.proceed(request);
                    }
                });

                retrofit = new Retrofit.Builder()
                        .baseUrl(BASE_URL)
                        .addConverterFactory(GsonConverterFactory.create())
                        .client(httpClient.build())
                        .build();
            }
            return retrofit;
        }
    }

    public class DataResponse {
        private List<String> parkings;

        public List<String> getParkings() {
            return parkings;
        }
    }

}
