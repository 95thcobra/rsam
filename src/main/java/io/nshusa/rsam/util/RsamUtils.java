package io.nshusa.rsam.util;

import io.nshusa.rsam.RSFileStore;
import io.nshusa.rsam.IndexedFileSystem;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Paths;

public class RsamUtils {

    private RsamUtils() {

    }

    public static void defragment(IndexedFileSystem fs) {


    }


    public static void main(String[] args) throws IOException {
        try(IndexedFileSystem fs = IndexedFileSystem.init(Paths.get("cache"))) {

            File dir = new File("./cache/defragmented/");

            if (dir.exists()) {
                dir.mkdirs();
            }

            File dataFile = new File(dir, "main_file_cache.dat");

            if (!dataFile.exists()) {
                dataFile.createNewFile();
            }

            for (int i = 0; i < fs.getStoreCount(); i++) {
                File idxFile = new File(dir, "main_file_cache.idx" + i);

                if (!idxFile.exists()) {
                    idxFile.createNewFile();
                }
            }

            IndexedFileSystem nFs = IndexedFileSystem.init(dir.toPath());

            for (int storeCount = 0; storeCount < fs.getStoreCount(); storeCount++) {

                RSFileStore fileStore = fs.getStore(storeCount);
                RSFileStore fileStoreCopy = nFs.getStore(storeCount);

                System.out.println("defragmenting index: " + storeCount);

                for (int file = 0; file < fileStore.getFileCount(); file++) {
                    ByteBuffer buffer = fileStore.readFile(file);
                    fileStoreCopy.writeFile(file, buffer == null ? new byte[0] : buffer.array());
                    System.out.println("copying file: " + file);
                }

            }

            nFs.close();

            System.out.println("finished");

        }
    }

}
