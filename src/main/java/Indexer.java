/* SimpleApp.java */
import org.apache.spark.api.java.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.sql.SparkSession;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import scala.Tuple2;
import scala.Tuple3;
import scala.Tuple4;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFunction;




public class Indexer {

	private static final Pattern PUNCTUATION = Pattern.compile("[([.,=;!?:\\/\"\'<>]+)]");
	private static final Pattern SPACE = Pattern.compile("[\\s]");

	public static void main(String[] args) {
		
		AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
                .withCredentials(new InstanceProfileCredentialsProvider())
                .build();
		
		
		SparkConf conf = new SparkConf().setAppName("Simple Application");
		JavaSparkContext sc = new JavaSparkContext(conf);
		//String sourceDir = "/home/cis555/workspace/spark-app/files/";
		String sourceDir = "s3://flyingmantisdata00/";
		
		sc.hadoopConfiguration().set("fs.s3n.awsAccessKeyId", args[0]);
		sc.hadoopConfiguration().set("fs.s3n.awsSecretAccessKey", args[1]);

		HashMap<String, Integer> docIdMap = new HashMap<String, Integer>();
		

		JavaPairRDD<String, Integer> wordtuples = null ;

		SparkSession spark = SparkSession
				.builder()
				.appName("JavaWordCount")
				.getOrCreate();
		
		

		int id = 1;

		//JavaPairRDD<String,String> webpagefiles = sc.wholeTextFiles(sourceDir).cache();
		JavaPairRDD<String,String> webpagefiles = sc.wholeTextFiles(sourceDir);
		
		JavaPairRDD<String,String> sample = sc.parallelizePairs(webpagefiles.take(2));
		
		
		
		JavaRDD<String> bodies = webpagefiles.values();
		JavaRDD<String> file_names = webpagefiles.keys();		

		
		//JavaRDD<Tuple2<String,String>> urlwordpairs = webpagefiles
		JavaRDD<Tuple2<String,String>> urlwordpairs = sample
				.flatMap(new FlatMapFunction<Tuple2<String, String>,Tuple2<String,String>>() {
			@Override
			public Iterator<Tuple2<String,String>> call(Tuple2<String, String> doctuple) {
				
				Document doc = Jsoup.parse(doctuple._2);
				
				String body_text = doc.body().text();
				
				//body_text.replaceAll(PUNCTUATION,   );
				body_text = body_text.replaceAll(PUNCTUATION.pattern(), "");
				System.out.println("body_text: " + body_text);
				
				
				String[] words = SPACE.split(body_text);
				ArrayList<Tuple2<String,String>> url_word_pair = new ArrayList<Tuple2<String,String>>();

				for( String word : words){
					url_word_pair.add(new Tuple2<String,String>(doctuple._1(), word));
				}

				return url_word_pair.listIterator();
			}
		});


		JavaPairRDD<Tuple2<String,String>,Integer> ones = urlwordpairs.mapToPair(new PairFunction<Tuple2<String,String>,Tuple2<String,String>,Integer>(){
			@Override
			public Tuple2< Tuple2<String,String>, Integer> call(Tuple2<String,String> wordpairs){
				return 
						new Tuple2<Tuple2<String,String>, Integer> (
								new Tuple2<String,String>(wordpairs._1(),wordpairs._2()),  
								1
								);
			}
		});


		JavaPairRDD<Tuple2<String, String>, Integer> counts = ones.reduceByKey(
				new Function2<Integer, Integer, Integer>(){
					@Override
					public Integer call(Integer i1, Integer i2) {
						return i1 + i2;
					}
				}
				);


		//		List<Tuple2<Tuple2<String, String>,Integer>> output = counts.collect();
		//		for (Tuple2<Tuple2<String, String>, Integer> tuple : output) {
		//			System.out.println("("+tuple._1()._1() + ", " +tuple._1()._2 + ")" + "-> " + tuple._2());
		//		}

		// for augmented tf, get the count of word with the maximum count in a document

		JavaPairRDD<String,Integer> countsPerDoc = counts.mapToPair(new PairFunction<Tuple2<Tuple2<String,String>, Integer>, String, Integer> (){

			@Override
			public Tuple2<String, Integer> call(Tuple2<Tuple2<String, String>, Integer> tuple) throws Exception {

				return new Tuple2<String, Integer> ( tuple._1()._1(), tuple._2() ) ;
			}

		});

		JavaPairRDD <String,Integer> maxCountsPerDoc = countsPerDoc.reduceByKey(new Function2<Integer,Integer,Integer> (){

			@Override
			public Integer call(Integer v1, Integer v2) throws Exception {
				// TODO Auto-generated method stub
				return new Integer (Math.max(v1.intValue(), v2.intValue()));
			}

		});

		JavaPairRDD<String, Tuple2<String,Integer>> countsPerDoc_docpair = counts.mapToPair(
				new PairFunction< Tuple2< Tuple2<String,String>, Integer>, 
				String, Tuple2<String, Integer>> (){ // result pair (String, Tuple2)

					@Override
					public Tuple2<String, Tuple2<String, Integer>> call(Tuple2<Tuple2<String, String>, Integer> tuple) throws Exception {

						return new Tuple2<String, Tuple2<String, Integer>> ( tuple._1()._1(), new Tuple2<String,Integer>( tuple._1()._2(), tuple._2()) ) ;
					}

				});



		JavaRDD<Tuple4<String,String,Integer, Double>> countsPerDoc_with_max = countsPerDoc_docpair.join(maxCountsPerDoc).map(
				new Function<Tuple2<String, Tuple2<Tuple2<String,Integer>,Integer>>, Tuple4<String,String,Integer,Double>> (){

					@Override
					public Tuple4<String, String, Integer, Double> call(
							Tuple2<String, Tuple2<Tuple2<String, Integer>, Integer>> tuple_pair) throws Exception {
						// TODO Auto-generated method stub
						String doc_url = tuple_pair._1();
						String word = tuple_pair._2()._1()._1();
						Integer count = tuple_pair._2()._1()._2();
						Double max_word_count = new Double(tuple_pair._2()._2());


						return new Tuple4<String, String, Integer, Double>(doc_url, word, count, new Double(  count.intValue() / max_word_count.doubleValue() ) );
					}

				});

		List<Tuple4<String,String,Integer, Double>> output = countsPerDoc_with_max.collect();
		for (Tuple4<String,String,Integer, Double> tuple : output) {
			System.out.println("("+tuple._1() + ", " +tuple._2() + ", " + tuple._3()+", "+ tuple._4() +")");
		}


		//		JavaRDD<Tuple4<String,String,Integer,Double>> docNode_tf = counts. (new Function<Tuple2<Tuple2<String,String>,Integer>, Tuple4<String,String,Integer,Double> >(){
		//
		//			@Override
		//			public Tuple4<String, String, Integer, Double> call(Tuple2<Tuple2<String, String>, Integer> wordcounts)
		//					throws Exception {
		//				
		//				JavaRDD<Integer> = wordcounts
		//				
		//				
		//			}
		//			
		//		});




		spark.stop();
		//sc.stop();
	}
}








